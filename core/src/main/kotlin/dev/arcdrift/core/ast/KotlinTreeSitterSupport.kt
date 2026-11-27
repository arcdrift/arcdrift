package dev.arcdrift.core.ast

import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSTree
import org.treesitter.TreeSitterKotlin

/**
 * Parsed Tree-sitter Kotlin CST retaining a strong reference to [tree] and providing
 * exact UTF-8 byte-to-UTF-16 `String` char offset translation (O(1) fast path for ASCII).
 */
internal class ParsedKotlinCst(
    val source: String,
    val bytes: ByteArray,
    val tree: TSTree
) {
    val root: TSNode
        get() = tree.rootNode

    private val byteToCharTable: IntArray? by lazy {
        if (bytes.size == source.length) {
            null
        } else {
            buildByteToCharTable(source, bytes.size)
        }
    }

    fun startChar(node: TSNode): Int = byteToCharOffset(node.startByte)

    fun endChar(node: TSNode): Int = byteToCharOffset(node.endByte)

    fun byteToCharOffset(byteOffset: Int): Int {
        val table = byteToCharTable ?: return byteOffset.coerceIn(0, source.length)
        val clamped = byteOffset.coerceIn(0, table.lastIndex)
        return table[clamped]
    }

    fun slice(node: TSNode): String {
        if (node.isNull) return ""
        val s = startChar(node)
        val e = endChar(node)
        return if (s in 0..e && e <= source.length) source.substring(s, e) else ""
    }

    companion object {
        internal fun buildByteToCharTable(source: String, byteLen: Int): IntArray {
            val table = IntArray(byteLen + 1)
            var b = 0
            var c = 0
            val n = source.length
            while (c < n) {
                val ch = source[c]
                if (Character.isHighSurrogate(ch) && c + 1 < n && Character.isLowSurrogate(source[c + 1])) {
                    for (k in 0 until 4) {
                        if (b < byteLen) table[b++] = c
                    }
                    c += 2
                } else {
                    val utf8Len = when {
                        Character.isSurrogate(ch) -> 1 // Unpaired surrogate encodes as single '?' byte in UTF-8
                        ch.code < 0x80 -> 1
                        ch.code < 0x800 -> 2
                        else -> 3
                    }
                    for (k in 0 until utf8Len) {
                        if (b < byteLen) table[b++] = c
                    }
                    c += 1
                }
            }
            while (b <= byteLen) {
                table[b++] = n
            }
            return table
        }
    }
}

internal data class ParsedKotlinImport(
    val node: TSNode,
    val identifierNode: TSNode?,
    val fqName: String,
    val isWildcard: Boolean,
    val aliasName: String?
)

/**
 * Lock-free Tree-sitter Kotlin (`io.github.bonede:tree-sitter-kotlin`) parsing, CST traversal,
 * type/annotation extraction, and offset utilities.
 *
 * Uses a thread-local [TSParser] backed by a shared [TreeSitterKotlin] language descriptor so
 * multi-threaded scanning and refactoring run with zero global synchronization.
 */
object KotlinTreeSitterSupport {

    private val KOTLIN_LANGUAGE = TreeSitterKotlin()

    private val THREAD_LOCAL_PARSER: ThreadLocal<TSParser> = ThreadLocal.withInitial {
        TSParser().apply { setLanguage(KOTLIN_LANGUAGE) }
    }

    internal fun parse(source: String): ParsedKotlinCst {
        val parser = THREAD_LOCAL_PARSER.get()
        val bytes = source.toByteArray(Charsets.UTF_8)
        val tree = parser.parseString(null, source)
        if (containsRealSyntaxError(tree.rootNode)) {
            // tree-sitter-kotlin 0.3.x fails to recover nested single-line `object { ... } }` bodies.
            // Re-parse an offset-preserving shadow where horizontal whitespace before `}` (outside strings
            // and comments) becomes `\n`; only accept it if it parses cleanly, so genuinely broken code
            // still reports errors. All offsets/slices continue to resolve against the original [source].
            val shadow = buildBraceRecoveryShadow(source)
            if (shadow != source) {
                val shadowTree = parser.parseString(null, shadow)
                if (!containsRealSyntaxError(shadowTree.rootNode)) {
                    return ParsedKotlinCst(source = source, bytes = bytes, tree = shadowTree)
                }
            }
        }
        return ParsedKotlinCst(source = source, bytes = bytes, tree = tree)
    }

    /**
     * Returns a copy of [source] with identical length and UTF-8 byte length in which every space or tab
     * immediately preceding a code-level `}` is replaced by `\n`. String literals (including `${...}`
     * templates), character literals, and comments are left untouched.
     */
    internal fun buildBraceRecoveryShadow(source: String): String {
        val out = source.toCharArray()
        var changed = false
        val n = source.length

        fun skipLineComment(i: Int): Int {
            var j = i + 2
            while (j < n && source[j] != '\n' && source[j] != '\r') j++
            return j
        }

        fun skipBlockComment(i: Int): Int {
            var j = i + 2
            var depth = 1
            while (j < n && depth > 0) {
                if (j + 1 < n && source[j] == '/' && source[j + 1] == '*') {
                    depth++
                    j += 2
                } else if (j + 1 < n && source[j] == '*' && source[j + 1] == '/') {
                    depth--
                    j += 2
                } else {
                    j++
                }
            }
            return j
        }

        fun skipCharLiteral(i: Int): Int {
            var j = i + 1
            while (j < n && source[j] != '\'' && source[j] != '\n') {
                if (source[j] == '\\') j++
                j++
            }
            return (j + 1).coerceAtMost(n)
        }

        lateinit var scanCode: (Int, Boolean) -> Int

        fun skipString(i: Int): Int {
            val raw = i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"'
            var j = if (raw) i + 3 else i + 1
            while (j < n) {
                val c = source[j]
                if (raw) {
                    if (c == '"' && j + 2 < n && source[j + 1] == '"' && source[j + 2] == '"') {
                        j += 3
                        while (j < n && source[j] == '"') j++
                        return j
                    }
                } else {
                    if (c == '\\') {
                        j += 2
                        continue
                    }
                    if (c == '"' || c == '\n') return j + 1
                }
                if (c == '$' && j + 1 < n && source[j + 1] == '{') {
                    j = scanCode(j + 2, true)
                    continue
                }
                j++
            }
            return n
        }

        scanCode = { start, inTemplate ->
            var i = start
            var depth = 0
            var result = n
            while (i < n) {
                val c = source[i]
                when {
                    c == '/' && i + 1 < n && source[i + 1] == '/' -> i = skipLineComment(i)
                    c == '/' && i + 1 < n && source[i + 1] == '*' -> i = skipBlockComment(i)
                    c == '"' -> i = skipString(i)
                    c == '\'' -> i = skipCharLiteral(i)
                    c == '{' -> {
                        depth++
                        i++
                    }
                    c == '}' -> {
                        if (inTemplate && depth == 0) {
                            result = i + 1
                            break
                        }
                        depth--
                        if (!inTemplate && i > 0 && (out[i - 1] == ' ' || out[i - 1] == '\t')) {
                            out[i - 1] = '\n'
                            changed = true
                        }
                        i++
                    }
                    else -> i++
                }
            }
            result
        }

        scanCode(0, false)
        return if (changed) String(out) else source
    }

    private fun containsRealSyntaxError(root: TSNode): Boolean {
        if (root.isNull || !root.hasError()) return false
        fun scan(node: TSNode): Boolean {
            if (node.isNull) return false
            if (node.isMissing) return node.type != "_automatic_semicolon"
            if (node.type == "ERROR") return true
            if (!node.hasError()) return false
            var hasErrorChild = false
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child.isMissing || child.hasError()) {
                    hasErrorChild = true
                    if (scan(child)) return true
                }
            }
            if (!hasErrorChild) {
                val sexp = node.toString().replace("(MISSING _automatic_semicolon)", "")
                return sexp.contains("MISSING") || sexp.contains("ERROR")
            }
            return false
        }
        return scan(root)
    }

    internal fun isCommentNode(node: TSNode): Boolean =
        !node.isNull && (node.type == "line_comment" || node.type == "multiline_comment")

    internal fun hasSyntaxErrors(cst: ParsedKotlinCst): Boolean = containsRealSyntaxError(cst.root)

    internal fun children(node: TSNode): List<TSNode> {
        if (node.isNull || node.childCount == 0) return emptyList()
        return List(node.childCount) { node.getChild(it) }
    }

    internal fun namedChildren(node: TSNode): List<TSNode> {
        if (node.isNull || node.namedChildCount == 0) return emptyList()
        return List(node.namedChildCount) { node.getNamedChild(it) }
    }

    internal fun firstChildOfType(node: TSNode, type: String): TSNode? {
        if (node.isNull) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == type) return child
        }
        return null
    }

    internal fun childrenOfType(node: TSNode, type: String): List<TSNode> {
        if (node.isNull || node.childCount == 0) return emptyList()
        val result = mutableListOf<TSNode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == type) result.add(child)
        }
        return result
    }

    internal fun walkNamed(node: TSNode, visitor: (TSNode) -> Unit) {
        if (node.isNull) return
        visitor(node)
        for (i in 0 until node.namedChildCount) {
            walkNamed(node.getNamedChild(i), visitor)
        }
    }

    internal fun readPackageHeaderNode(cst: ParsedKotlinCst): TSNode? =
        firstChildOfType(cst.root, "package_header")

    internal fun readPackageName(cst: ParsedKotlinCst): String {
        val pkgHeader = readPackageHeaderNode(cst) ?: return ""
        val idNode = firstChildOfType(pkgHeader, "identifier") ?: return ""
        return cst.slice(idNode).replace(Regex("""\s+"""), "")
    }

    internal fun readImports(cst: ParsedKotlinCst): List<ParsedKotlinImport> {
        val result = mutableListOf<ParsedKotlinImport>()
        for (child in namedChildren(cst.root)) {
            if (child.type == "import_list") {
                for (imp in childrenOfType(child, "import_header")) {
                    parseImportHeader(cst, imp)?.let { result.add(it) }
                }
            } else if (child.type == "import_header") {
                parseImportHeader(cst, child)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun parseImportHeader(cst: ParsedKotlinCst, imp: TSNode): ParsedKotlinImport? {
        val idNode = firstChildOfType(imp, "identifier") ?: return null
        val fq = cst.slice(idNode).replace(Regex("""\s+"""), "")
        if (fq.isEmpty()) return null
        val isWildcard = firstChildOfType(imp, "wildcard_import") != null
        val aliasNode = firstChildOfType(imp, "import_alias")
        val aliasName = aliasNode?.let { a ->
            val nameNode = firstChildOfType(a, "type_identifier") ?: firstChildOfType(a, "simple_identifier")
            nameNode?.let { cst.slice(it).trim() }?.takeIf { it.isNotEmpty() }
        }
        return ParsedKotlinImport(
            node = imp,
            identifierNode = idNode,
            fqName = fq,
            isWildcard = isWildcard,
            aliasName = aliasName
        )
    }

    internal fun isClassOrObjectNode(node: TSNode): Boolean =
        node.type == "class_declaration" || node.type == "object_declaration"

    internal fun classDeclarationNameNode(decl: TSNode): TSNode? =
        firstChildOfType(decl, "type_identifier") ?: firstChildOfType(decl, "simple_identifier")

    internal fun classDeclarationName(cst: ParsedKotlinCst, decl: TSNode): String? =
        classDeclarationNameNode(decl)?.let { cst.slice(it).trim() }?.takeIf { it.isNotEmpty() }

    internal fun classBodyNode(decl: TSNode): TSNode? =
        firstChildOfType(decl, "class_body") ?: firstChildOfType(decl, "enum_class_body")

    internal fun directBodyDeclarations(decl: TSNode): List<TSNode> {
        val body = classBodyNode(decl)
        if (body != null) return namedChildren(body)
        // Workaround for tree-sitter-kotlin 0.3.8 greedy `by impl { ... }` parsing, where
        // the class body block is parsed as a trailing lambda on the `explicit_delegation` expression.
        val lastDeleg = childrenOfType(decl, "delegation_specifier").lastOrNull() ?: return emptyList()
        val explicitDeleg = firstChildOfType(lastDeleg, "explicit_delegation") ?: return emptyList()
        val callExpr = firstChildOfType(explicitDeleg, "call_expression") ?: return emptyList()
        val suffix = firstChildOfType(callExpr, "call_suffix") ?: return emptyList()
        val annotatedLambda = firstChildOfType(suffix, "annotated_lambda") ?: return emptyList()
        val lambdaLit = firstChildOfType(annotatedLambda, "lambda_literal") ?: return emptyList()
        val stmts = firstChildOfType(lambdaLit, "statements") ?: return emptyList()
        return namedChildren(stmts)
    }

    internal fun delegationSpecifierHeaderEndChar(cst: ParsedKotlinCst, delegSpec: TSNode): Int {
        val explicitDeleg = firstChildOfType(delegSpec, "explicit_delegation")
        val callExpr = explicitDeleg?.let { firstChildOfType(it, "call_expression") }
        val suffix = callExpr?.let { firstChildOfType(it, "call_suffix") }
        val annotatedLambda = suffix?.let { firstChildOfType(it, "annotated_lambda") }
        if (annotatedLambda != null && firstChildOfType(suffix, "value_arguments") == null) {
            val callee = callCalleeNode(callExpr)
            if (callee != null) return cst.endChar(callee)
        }
        return cst.endChar(delegSpec)
    }

    internal fun hasAnonToken(node: TSNode, tokenText: String): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (!child.isNamed && child.type == tokenText) return true
        }
        return false
    }

    internal fun hasModifierKeyword(cst: ParsedKotlinCst, decl: TSNode, keyword: String): Boolean {
        val mods = firstChildOfType(decl, "modifiers") ?: return false
        for (child in namedChildren(mods)) {
            if (child.type != "annotation" && cst.slice(child).trim() == keyword) {
                return true
            }
        }
        return false
    }

    internal fun findModifierKeywordNode(cst: ParsedKotlinCst, decl: TSNode, keyword: String): TSNode? {
        val mods = firstChildOfType(decl, "modifiers") ?: return null
        for (child in namedChildren(mods)) {
            if (child.type != "annotation" && cst.slice(child).trim() == keyword) {
                return child
            }
        }
        return null
    }

    internal fun isInterfaceDecl(decl: TSNode): Boolean =
        decl.type == "class_declaration" && hasAnonToken(decl, "interface")

    internal fun isEnumDecl(cst: ParsedKotlinCst, decl: TSNode): Boolean =
        decl.type == "class_declaration" &&
            (hasAnonToken(decl, "enum") || hasModifierKeyword(cst, decl, "enum") || firstChildOfType(decl, "enum_class_body") != null)

    internal fun isDataOrValueClass(cst: ParsedKotlinCst, decl: TSNode): Boolean =
        (decl.type == "object_declaration" && hasModifierKeyword(cst, decl, "data")) ||
            decl.type == "class_declaration" && (
                hasModifierKeyword(cst, decl, "data") ||
                    hasModifierKeyword(cst, decl, "value") ||
                    hasModifierKeyword(cst, decl, "inline")
                )

    internal fun extractAnnotations(cst: ParsedKotlinCst, node: TSNode): List<AstAnnotation> {
        val mods = firstChildOfType(node, "modifiers") ?: return emptyList()
        return extractAnnotationsFromModifiersNode(cst, mods)
    }

    internal fun extractAnnotationsFromModifiersNode(cst: ParsedKotlinCst, modsNode: TSNode): List<AstAnnotation> {
        val result = mutableListOf<AstAnnotation>()
        for (ann in childrenOfType(modsNode, "annotation")) {
            result.addAll(convertAnnotationNode(cst, ann))
        }
        return result
    }

    internal fun extractFileAnnotations(cst: ParsedKotlinCst): List<AstAnnotation> {
        val result = mutableListOf<AstAnnotation>()
        for (fa in childrenOfType(cst.root, "file_annotation")) {
            result.addAll(convertAnnotationNode(cst, fa))
        }
        return result
    }

    internal fun convertAnnotationNode(cst: ParsedKotlinCst, annNode: TSNode): List<AstAnnotation> {
        val rawText = cst.slice(annNode).trim()
        val out = mutableListOf<AstAnnotation>()

        fun collectDirect(container: TSNode) {
            for (child in namedChildren(container)) {
                when (child.type) {
                    "constructor_invocation" -> {
                        val uType = firstChildOfType(child, "user_type")
                        val shortName = uType?.let { qualifiedUserTypeText(cst, it).substringAfterLast('.') } ?: "Annotation"
                        val valueArgsNode = firstChildOfType(child, "value_arguments")
                        out.add(buildAstAnnotation(cst, shortName, valueArgsNode, rawText))
                    }
                    "user_type" -> {
                        val shortName = qualifiedUserTypeText(cst, child).substringAfterLast('.')
                        out.add(AstAnnotation(name = shortName, rawTokensText = rawText))
                    }
                    "multi_annotation", "single_annotation" -> collectDirect(child)
                }
            }
        }
        collectDirect(annNode)

        if (out.isEmpty()) {
            val fallbackName = rawText.removePrefix("@").substringAfter(':').substringBefore('(').substringAfterLast('.').trim()
            if (fallbackName.isNotEmpty()) {
                out.add(AstAnnotation(name = fallbackName, rawTokensText = rawText))
            }
        }
        return out
    }

    private fun buildAstAnnotation(
        cst: ParsedKotlinCst,
        shortName: String,
        valueArgsNode: TSNode?,
        rawTokensText: String
    ): AstAnnotation {
        val named = linkedMapOf<String, List<String>>()
        val positional = mutableListOf<String>()

        if (valueArgsNode != null) {
            for (arg in childrenOfType(valueArgsNode, "value_argument")) {
                val (argName, exprNode) = splitValueArgument(cst, arg)
                val vals = if (exprNode != null) extractExprStrings(cst, exprNode) else emptyList()
                if (!argName.isNullOrEmpty()) {
                    named[argName] = vals
                    if (argName in setOf("value", "path", "topics", "queues")) {
                        positional.addAll(vals)
                    }
                } else {
                    positional.addAll(vals)
                }
            }
        }

        return AstAnnotation(
            name = shortName,
            namedArgs = named,
            positionalStrings = positional,
            rawTokensText = rawTokensText
        )
    }

    internal fun splitValueArgument(cst: ParsedKotlinCst, argNode: TSNode): Pair<String?, TSNode?> {
        var hasEquals = false
        var argName: String? = null
        var exprNode: TSNode? = null
        for (child in children(argNode)) {
            if (!child.isNamed && child.type == "=") {
                hasEquals = true
                continue
            }
            if (!child.isNamed || isCommentNode(child)) continue
            if (!hasEquals && argName == null && exprNode == null) {
                exprNode = child
            } else if (hasEquals) {
                if (argName == null && exprNode != null && exprNode.type == "simple_identifier") {
                    argName = cst.slice(exprNode).trim()
                }
                exprNode = child
                break
            }
        }
        return if (hasEquals) argName to exprNode else null to exprNode
    }

    internal fun extractExprStrings(cst: ParsedKotlinCst, expr: TSNode): List<String> {
        return when (expr.type) {
            "string_literal", "multiline_string_literal" -> listOfNotNull(extractStringLiteral(cst, expr))
            "collection_literal" -> namedChildren(expr).flatMap { extractExprStrings(cst, it) }
            "call_expression" -> {
                val callee = callCalleeText(cst, expr)
                if (callee == "arrayOf" || callee == "listOf") {
                    callValueArguments(expr).mapNotNull { splitValueArgument(cst, it).second }
                        .flatMap { extractExprStrings(cst, it) }
                } else {
                    listOf(cst.slice(expr).trim())
                }
            }
            else -> listOf(cst.slice(expr).trim().removeSurrounding("\""))
        }
    }

    internal fun extractStringLiteral(cst: ParsedKotlinCst, node: TSNode?): String? {
        if (node == null || node.isNull) return null
        return when (node.type) {
            "string_literal" -> {
                val raw = cst.slice(node)
                when {
                    raw.startsWith("\"\"\"") && raw.endsWith("\"\"\"") && raw.length >= 6 ->
                        raw.substring(3, raw.length - 3)
                    raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
                        raw.substring(1, raw.length - 1)
                    else -> raw
                }
            }
            "multiline_string_literal" -> {
                val raw = cst.slice(node)
                if (raw.startsWith("\"\"\"") && raw.endsWith("\"\"\"") && raw.length >= 6) {
                    raw.substring(3, raw.length - 3)
                } else {
                    raw
                }
            }
            else -> null
        }
    }

    internal fun qualifiedUserTypeText(cst: ParsedKotlinCst, userType: TSNode): String {
        val parts = mutableListOf<String>()
        for (child in namedChildren(userType)) {
            if (child.type == "type_identifier" || child.type == "simple_identifier") {
                val t = cst.slice(child).trim()
                if (t.isNotEmpty()) parts.add(t)
            }
        }
        return parts.joinToString(".").ifEmpty { cst.slice(userType).substringBefore('<').trim().ifEmpty { "Any" } }
    }

    internal fun userTypeNameEndChar(cst: ParsedKotlinCst, userType: TSNode): Int {
        var lastIdEnd = -1
        for (child in namedChildren(userType)) {
            if (child.type == "type_identifier" || child.type == "simple_identifier") {
                lastIdEnd = cst.endChar(child)
            }
        }
        return if (lastIdEnd >= 0) lastIdEnd else cst.endChar(userType)
    }

    internal fun isTypeNode(node: TSNode): Boolean =
        node.type in TYPE_NODE_KINDS

    private val TYPE_NODE_KINDS = setOf(
        "user_type",
        "nullable_type",
        "function_type",
        "parenthesized_type",
        "dynamic",
        "not_nullable_type"
    )

    internal fun convertTypeNode(
        cst: ParsedKotlinCst,
        typeNode: TSNode?,
        referencedSimple: MutableSet<String>,
        referencedFq: MutableSet<String>,
        isNullable: Boolean = false
    ): AstTypeRef {
        if (typeNode == null || typeNode.isNull) return AstTypeRef("Any", isNullable = isNullable)
        return when (typeNode.type) {
            "nullable_type" -> {
                val inner = namedChildren(typeNode).firstOrNull { isTypeNode(it) }
                if (inner != null) {
                    convertTypeNode(cst, inner, referencedSimple, referencedFq, isNullable = true)
                } else {
                    AstTypeRef("Any", isNullable = true)
                }
            }
            "parenthesized_type" -> {
                val inner = namedChildren(typeNode).firstOrNull { isTypeNode(it) }
                if (inner != null) {
                    convertTypeNode(cst, inner, referencedSimple, referencedFq, isNullable = isNullable)
                } else {
                    AstTypeRef(cst.slice(typeNode).trim(), isNullable = isNullable)
                }
            }
            "user_type" -> {
                val fullRawName = qualifiedUserTypeText(cst, typeNode)
                val simpleName = fullRawName.substringAfterLast('.')
                if (fullRawName.contains('.')) {
                    referencedFq.add(fullRawName)
                } else if (simpleName.isNotEmpty() && simpleName[0].isUpperCase()) {
                    referencedSimple.add(simpleName)
                }
                val typeArgsNode = firstChildOfType(typeNode, "type_arguments")
                val args = if (typeArgsNode != null) {
                    childrenOfType(typeArgsNode, "type_projection").map { proj ->
                        val innerType = namedChildren(proj).firstOrNull { isTypeNode(it) }
                        if (innerType != null) {
                            convertTypeNode(cst, innerType, referencedSimple, referencedFq)
                        } else {
                            AstTypeRef(cst.slice(proj).trim().ifEmpty { "*" })
                        }
                    }
                } else {
                    emptyList()
                }
                AstTypeRef(rawName = fullRawName, typeArguments = args, isNullable = isNullable)
            }
            else -> {
                // Collect nested user_types inside function_type etc.
                walkNamed(typeNode) { n ->
                    if (n.type == "user_type") {
                        convertTypeNode(cst, n, referencedSimple, referencedFq)
                    }
                }
                AstTypeRef(rawName = cst.slice(typeNode).trim(), isNullable = isNullable)
            }
        }
    }

    internal fun callCalleeNode(callExpr: TSNode): TSNode? {
        if (callExpr.type != "call_expression" || callExpr.namedChildCount == 0) return null
        val first = callExpr.getNamedChild(0)
        return if (first.type == "call_suffix") null else first
    }

    internal fun callCalleeText(cst: ParsedKotlinCst, callExpr: TSNode): String {
        val callee = callCalleeNode(callExpr) ?: return ""
        return cst.slice(callee).trim()
    }

    internal fun callValueArguments(callExpr: TSNode): List<TSNode> {
        val suffix = firstChildOfType(callExpr, "call_suffix") ?: return emptyList()
        val valArgs = firstChildOfType(suffix, "value_arguments") ?: return emptyList()
        return childrenOfType(valArgs, "value_argument")
    }

    internal fun inferExpressionReturnDto(
        cst: ParsedKotlinCst,
        fnBodyNode: TSNode?,
        params: List<AstParam> = emptyList(),
        fields: Map<String, String> = emptyMap()
    ): String? {
        if (fnBodyNode == null || fnBodyNode.isNull) return null
        // Expression body starts with `=`: `function_body -> '=' <expr>`
        if (!hasAnonToken(fnBodyNode, "=")) return null
        val expr = namedChildren(fnBodyNode).firstOrNull { !isCommentNode(it) } ?: return null
        return inferExprNodeReturnDto(cst, expr, params, fields)
    }

    private fun inferExprNodeReturnDto(
        cst: ParsedKotlinCst,
        expr: TSNode,
        params: List<AstParam>,
        fields: Map<String, String>
    ): String? {
        return when (expr.type) {
            "simple_identifier" -> {
                val refName = cst.slice(expr).trim()
                params.find { it.name == refName }?.type?.render() ?: fields[refName]
            }
            "string_literal", "multiline_string_literal" -> "String"
            "boolean_literal" -> "Boolean"
            "long_literal" -> "Long"
            "real_literal" -> "Double"
            "integer_literal" -> {
                val txt = cst.slice(expr).trim()
                if (txt.endsWith("L") || txt.endsWith("l")) "Long" else "Int"
            }
            "call_expression" -> {
                val calleeNode = callCalleeNode(expr) ?: return null
                if (calleeNode.type == "simple_identifier") {
                    val callee = cst.slice(calleeNode).trim()
                    if (callee.isNotEmpty() && callee[0].isUpperCase()) {
                        return callee
                    }
                    return when (callee) {
                        "listOf", "emptyList", "mutableListOf" -> "List<Any>"
                        "setOf", "emptySet", "mutableSetOf" -> "Set<Any>"
                        "mapOf", "emptyMap", "mutableMapOf" -> "Map<Any, Any>"
                        else -> null
                    }
                }
                if (calleeNode.type == "navigation_expression") {
                    val navChildren = namedChildren(calleeNode)
                    val receiver = navChildren.firstOrNull()?.let { cst.slice(it).trim() }
                    if (receiver == "ResponseEntity") {
                        val firstArgExpr = callValueArguments(expr).firstOrNull()?.let { splitValueArgument(cst, it).second }
                        if (firstArgExpr != null) {
                            if (firstArgExpr.type == "call_expression") {
                                val innerCallee = callCalleeText(cst, firstArgExpr)
                                if (innerCallee.isNotEmpty() && innerCallee[0].isUpperCase()) {
                                    return innerCallee
                                }
                            } else if (firstArgExpr.type == "simple_identifier") {
                                val refName = cst.slice(firstArgExpr).trim()
                                val resolved = params.find { it.name == refName }?.type?.render() ?: fields[refName]
                                if (!resolved.isNullOrEmpty()) return resolved
                            }
                        }
                    }
                }
                null
            }
            else -> null
        }
    }

    fun offsetToLine(source: String, offset: Int): Int {
        val clamped = offset.coerceIn(0, source.length)
        var line = 1
        for (i in 0 until clamped) {
            if (source[i] == '\n') line++
        }
        return line
    }
}
