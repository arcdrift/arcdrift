package dev.arcdrift.intellij.psi

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.util.PsiTypesUtil
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.EdgeKind
import dev.arcdrift.core.model.MemberSignature
import dev.arcdrift.core.model.SourceLocation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Extracts an [ArcNode] and raw dependency triples from an IntelliJ UAST [UClass].
 */
internal object UastClassExtractor {

    fun processUClass(
        uClass: UClass,
        parentPackage: String?,
        fileImports: List<String>,
        dtoFieldsBySimpleName: Map<String, Map<String, String>>,
        nodesById: MutableMap<String, ArcNode>,
        rawDependencies: MutableList<Triple<String, String, String>>,
        fallbackFilePath: String? = null
    ) {
        val fqName = uClass.qualifiedName ?: return
        val label = uClass.name ?: fqName.substringAfterLast('.')
        val stereotypes = SpringHiltSemanticEnricher.extractSimpleStereotypes(uClass)
        val kind = SpringHiltSemanticEnricher.classifyNodeKind(uClass, stereotypes)
        val contracts = SpringHiltSemanticEnricher.extractRestContracts(uClass, dtoFieldsBySimpleName)

        val members = mutableListOf<MemberSignature>()
        for (field in uClass.fields) {
            val fieldTypeFq = field.type.canonicalText.substringBefore('<')
            rawDependencies.add(Triple(fqName, fieldTypeFq, EdgeKind.DEPENDS_ON))
            members.add(
                MemberSignature(
                    name = field.name,
                    kind = "FIELD",
                    typeOrSignature = field.type.presentableText
                )
            )
        }

        for (method in uClass.methods) {
            if (!method.isConstructor) {
                members.add(
                    MemberSignature(
                        name = method.name,
                        kind = "METHOD",
                        typeOrSignature = "(${method.uastParameters.joinToString(", ") { "${it.name}: ${it.type.presentableText}" }}): ${method.returnType?.presentableText ?: "Unit"}"
                    )
                )
            }
            for (param in method.uastParameters) {
                val paramTypeFq = param.type.canonicalText.substringBefore('<')
                rawDependencies.add(Triple(fqName, paramTypeFq, EdgeKind.DEPENDS_ON))
            }
            method.returnType?.canonicalText?.substringBefore('<')?.let { retFq ->
                rawDependencies.add(Triple(fqName, retFq, EdgeKind.DEPENDS_ON))
            }

            method.uastBody?.accept(object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    val resolvedMethod = runPsiSafely { node.resolve() }.getOrNull()
                    val fallbackCallee = node.methodName ?: node.methodIdentifier?.name
                    val targetClassFq = resolvedMethod?.containingClass?.qualifiedName
                        ?: runPsiSafely { PsiTypesUtil.getPsiClass(node.returnType)?.qualifiedName }.getOrNull()
                        ?: fallbackCallee?.takeIf { it.isNotEmpty() && it[0].isUpperCase() }
                    if (targetClassFq != null) {
                        rawDependencies.add(Triple(fqName, targetClassFq, EdgeKind.DEPENDS_ON))
                    }
                    return false
                }

                override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression): Boolean {
                    val resolved = runPsiSafely { node.resolve() }.getOrNull()
                    val fallbackQual = node.asRenderString().substringBefore('(').trim()
                    val targetClassFq = when (resolved) {
                        is PsiClass -> resolved.qualifiedName
                        is PsiMember -> resolved.containingClass?.qualifiedName
                        else -> runPsiSafely { PsiTypesUtil.getPsiClass(node.getExpressionType())?.qualifiedName }.getOrNull()
                            ?: fallbackQual.takeIf { it.isNotEmpty() }
                    }
                    if (targetClassFq != null) {
                        rawDependencies.add(Triple(fqName, targetClassFq, EdgeKind.DEPENDS_ON))
                    }
                    return false
                }

                override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                    val resolved = runPsiSafely { node.resolve() }.getOrNull()
                    val ident = node.identifier
                    val targetClassFq = when (resolved) {
                        is PsiClass -> resolved.qualifiedName
                        is PsiMember -> resolved.containingClass?.qualifiedName
                        else -> ident.takeIf { it.isNotEmpty() && it[0].isUpperCase() }
                    }
                    if (targetClassFq != null) {
                        rawDependencies.add(Triple(fqName, targetClassFq, EdgeKind.DEPENDS_ON))
                    }
                    return false
                }
            })
        }

        for (superType in uClass.uastSuperTypes) {
            val superFq = superType.type.canonicalText.substringBefore('<')
            rawDependencies.add(Triple(fqName, superFq, EdgeKind.IMPLEMENTS))
        }

        for (imp in fileImports) {
            rawDependencies.add(Triple(fqName, imp, EdgeKind.DEPENDS_ON))
        }

        if (fqName in nodesById) {
            return
        }

        val vFile = uClass.sourcePsi?.containingFile?.virtualFile
        val resolvedPath = fallbackFilePath ?: vFile?.path
        val doc = uClass.sourcePsi?.containingFile?.viewProvider?.document
        val startLine = doc?.let { d -> uClass.sourcePsi?.textRange?.startOffset?.let { d.getLineNumber(it) + 1 } } ?: 1
        val endLine = doc?.let { d -> uClass.sourcePsi?.textRange?.endOffset?.let { d.getLineNumber(it.coerceAtMost(d.textLength)) + 1 } } ?: 1
        val sourceLoc = resolvedPath?.let { pathStr ->
            SourceLocation(
                filePath = pathStr,
                startLine = startLine,
                endLine = endLine,
                language = if (pathStr.endsWith(".kt", ignoreCase = true)) "kotlin" else "java"
            )
        }

        nodesById[fqName] = ArcNode(
            id = fqName,
            label = label,
            c4Level = C4Level.L3_COMPONENT,
            kind = kind,
            parentId = parentPackage,
            stereotypes = stereotypes,
            members = members,
            contracts = contracts,
            source = sourceLoc
        )
    }
}
