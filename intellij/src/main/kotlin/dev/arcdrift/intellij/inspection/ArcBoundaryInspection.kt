package dev.arcdrift.intellij.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMember
import com.intellij.psi.util.PsiTypesUtil
import dev.arcdrift.core.model.*
import dev.arcdrift.core.rules.ArcRuleEngine
import dev.arcdrift.intellij.psi.runPsiSafely
import dev.arcdrift.intellij.service.ArcDriftProjectService
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.toUElement

/**
 * Real-time IntelliJ editor inspection that checks import statements, method calls, constructor
 * invocations, and qualified references in Java and Kotlin files against active `.arc.mmd`
 * [BoundaryRule]s and highlights architectural boundary violations immediately.
 */
class ArcBoundaryInspection : LocalInspectionTool() {

    override fun getDisplayName(): String =
        "ArcDrift architectural boundary violation"

    override fun getGroupDisplayName(): String =
        "ArcDrift Architecture"

    override fun getShortName(): String =
        "ArcBoundaryViolation"

    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val project = holder.project
        val service = ArcDriftProjectService.getInstance(project)
        if (service.currentGraph.nodes.isEmpty() && service.currentGraph.rules.isEmpty()) {
            service.scheduleRefreshProjectGraph(50)
        }
        val graph = service.currentGraph
        val rules = graph.rules
        if (rules.isEmpty()) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        val nodeMap = graph.nodeMap()
        val knownNodeIds = nodeMap.keys
        val simpleNameToFqIds = graph.nodes
            .filter { it.c4Level == C4Level.L3_COMPONENT }
            .groupBy({ it.label }, { it.id })

        val psiFile = holder.file
        val ktFile = psiFile as? KtFile
        val uFile = runPsiSafely { psiFile.toUElement(UFile::class.java) }.getOrNull()
        if (ktFile == null && uFile == null) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        val pkgName = ktFile?.packageFqName?.asString() ?: runPsiSafely { uFile?.packageName }.getOrNull().orEmpty()
        val baseFileName = psiFile.name.substringBeforeLast('.')
        val ktPrimaryClassName = ktFile?.declarations?.filterIsInstance<KtClassOrObject>()?.firstOrNull()?.name
        val uPrimaryClassFq = runPsiSafely { uFile?.classes?.firstOrNull()?.qualifiedName }.getOrNull()
        val primaryClass = uPrimaryClassFq
            ?: (if (ktPrimaryClassName != null) {
                if (pkgName.isNotEmpty()) "$pkgName.$ktPrimaryClassName" else ktPrimaryClassName
            } else {
                if (pkgName.isNotEmpty()) "$pkgName.$baseFileName" else baseFileName
            })

        val explicitImportsBySimple = linkedMapOf<String, String>()
        val wildcardImportPkgs = mutableListOf<String>()
        if (ktFile != null) {
            for (imp in ktFile.importDirectives) {
                val fq = imp.importedFqName?.asString() ?: continue
                if (imp.isAllUnder) {
                    wildcardImportPkgs.add(fq)
                } else {
                    val key = imp.aliasName?.takeIf { it.isNotEmpty() } ?: fq.substringAfterLast('.')
                    explicitImportsBySimple[key] = fq
                }
            }
        } else if (uFile != null) {
            for (uImp in runPsiSafely { uFile.imports }.getOrDefault(emptyList())) {
                val fq = uImp.importReference?.asRenderString()?.removeSuffix(".*") ?: continue
                if (uImp.isOnDemand) {
                    wildcardImportPkgs.add(fq)
                } else {
                    explicitImportsBySimple[fq.substringAfterLast('.')] = fq
                }
            }
        }

        return object : PsiElementVisitor() {
            private fun resolveEnclosingSourceFq(element: PsiElement): String {
                var curr: PsiElement? = element
                while (curr != null && curr !is com.intellij.psi.PsiFile) {
                    if (curr is KtClassOrObject) {
                        val name = curr.name
                        if (!name.isNullOrEmpty()) {
                            return curr.fqName?.asString() ?: if (pkgName.isNotEmpty()) "$pkgName.$name" else name
                        }
                    } else if (curr is PsiClass) {
                        val qName = curr.qualifiedName ?: curr.name
                        if (!qName.isNullOrEmpty()) {
                            return qName
                        }
                    }
                    curr = curr.parent
                }
                return primaryClass
            }

            private fun extractKtRawTypeName(typeElement: KtTypeElement?): String? {
                if (typeElement == null) return null
                if (typeElement is KtNullableType) {
                    return extractKtRawTypeName(typeElement.innerType)
                }
                if (typeElement is KtUserType) {
                    val qual = typeElement.qualifier?.text?.replace(Regex("\\s+"), "")
                    val ref = typeElement.referencedName ?: return null
                    return if (!qual.isNullOrEmpty()) "$qual.$ref" else ref
                }
                return null
            }

            private fun resolveSimpleNameTarget(sourceClassFq: String, simpleName: String): String? {
                if (simpleName.isEmpty()) return null
                val explicitImportFq = explicitImportsBySimple[simpleName]
                if (explicitImportFq != null) {
                    // Explicitly imported symbol: only match if that exact FQN is a project node (never shadow external imports!)
                    return explicitImportFq.takeIf { it in knownNodeIds && it != sourceClassFq }
                }
                val srcPkg = sourceClassFq.substringBeforeLast('.', "")
                val samePkgCandidate = if (srcPkg.isNotEmpty()) "$srcPkg.$simpleName" else simpleName
                if (samePkgCandidate in knownNodeIds && samePkgCandidate != sourceClassFq) {
                    return samePkgCandidate
                }
                val wildcardMatches = wildcardImportPkgs
                    .map { "$it.$simpleName" }
                    .filter { it in knownNodeIds && it != sourceClassFq }
                    .distinct()
                if (wildcardMatches.size == 1) {
                    return wildcardMatches.first()
                }
                if (explicitImportsBySimple.isEmpty() && wildcardImportPkgs.isEmpty()) {
                    val candidates = simpleNameToFqIds[simpleName].orEmpty()
                    if (candidates.size == 1) return candidates.first()
                }
                return null
            }

            override fun visitElement(element: PsiElement) {
                // Skip leaf tokens (whitespace, punctuation, identifiers) to avoid redundant UAST conversions
                if (element.firstChild == null) return

                if (element is KtImportDirective) {
                    val rawImportedFq = element.importedFqName?.asString() ?: return
                    val importedFq = if (element.isAllUnder && !rawImportedFq.endsWith(".*")) {
                        "$rawImportedFq.*"
                    } else {
                        rawImportedFq
                    }
                    checkAndReportViolation(element, primaryClass, importedFq, EdgeKind.DEPENDS_ON)
                    return
                }

                if (element is KtTypeReference) {
                    val sourceClassFq = resolveEnclosingSourceFq(element)
                    val rawTypeText = extractKtRawTypeName(element.typeElement)
                        ?: element.typeElement?.text?.substringBefore('<')?.removeSuffix("?")?.trim()
                        ?: return
                    val targetClassFq = when {
                        rawTypeText in knownNodeIds -> rawTypeText
                        rawTypeText.contains('.') -> rawTypeText.takeIf { it in knownNodeIds }
                        else -> resolveSimpleNameTarget(sourceClassFq, rawTypeText)
                    }
                    if (targetClassFq != null && targetClassFq != sourceClassFq) {
                        checkAndReportViolation(element, sourceClassFq, targetClassFq, EdgeKind.DEPENDS_ON)
                    }
                    return
                }

                val uImport = runPsiSafely { element.toUElement(UImportStatement::class.java) }.getOrNull()
                if (uImport != null && uImport.sourcePsi == element) {
                    val rawImportedFq = uImport.importReference?.asRenderString() ?: return
                    val importedFq = if (uImport.isOnDemand && !rawImportedFq.endsWith(".*")) {
                        "$rawImportedFq.*"
                    } else {
                        rawImportedFq
                    }
                    checkAndReportViolation(element, primaryClass, importedFq, EdgeKind.DEPENDS_ON)
                    return
                }

                val uCall = runPsiSafely { element.toUElement(UCallExpression::class.java) }.getOrNull()
                if (uCall != null && uCall.sourcePsi == element) {
                    val sourceClassFq = resolveEnclosingSourceFq(element)
                    val resolvedMethod = runPsiSafely { uCall.resolve() }.getOrNull()
                    val methodName = uCall.methodName ?: uCall.methodIdentifier?.name
                    val targetClassFq = resolvedMethod?.containingClass?.qualifiedName
                        ?: runPsiSafely { PsiTypesUtil.getPsiClass(uCall.returnType)?.qualifiedName }.getOrNull()
                        ?: methodName?.takeIf { it.isNotEmpty() && it[0].isUpperCase() }
                            ?.let { resolveSimpleNameTarget(sourceClassFq, it) }
                    if (targetClassFq != null && targetClassFq != sourceClassFq) {
                        checkAndReportViolation(element, sourceClassFq, targetClassFq, EdgeKind.CALLS)
                    }
                    return
                }

                val uQual = runPsiSafely { element.toUElement(UQualifiedReferenceExpression::class.java) }.getOrNull()
                if (uQual != null && uQual.sourcePsi == element) {
                    val sourceClassFq = resolveEnclosingSourceFq(element)
                    val resolved = runPsiSafely { uQual.resolve() }.getOrNull()
                    val rawQualText = uQual.asRenderString().substringBefore('(').trim()
                    val targetClassFq = when (resolved) {
                        is PsiClass -> resolved.qualifiedName
                        is PsiMember -> resolved.containingClass?.qualifiedName
                        else -> when {
                            rawQualText in knownNodeIds -> rawQualText
                            rawQualText.substringBeforeLast('.', "") in knownNodeIds -> rawQualText.substringBeforeLast('.')
                            else -> resolveSimpleNameTarget(sourceClassFq, rawQualText.substringBefore('.'))
                        }
                    }
                    if (targetClassFq != null && targetClassFq != sourceClassFq) {
                        checkAndReportViolation(element, sourceClassFq, targetClassFq, EdgeKind.DEPENDS_ON)
                    }
                }
            }

            private fun checkAndReportViolation(
                element: PsiElement,
                sourceFq: String,
                targetFq: String,
                edgeKind: String
            ) {
                val srcNode = nodeMap[sourceFq] ?: ArcNode(
                    id = sourceFq,
                    label = sourceFq.substringAfterLast('.'),
                    parentId = sourceFq.substringBeforeLast('.', "").ifEmpty { null }
                )
                val tgtNode = nodeMap[targetFq] ?: ArcNode(
                    id = targetFq,
                    label = targetFq.substringAfterLast('.'),
                    parentId = targetFq.substringBeforeLast('.', "").ifEmpty { null }
                )
                val miniGraph = ArcGraph(
                    projectName = project.name,
                    rules = rules,
                    nodes = listOf(srcNode, tgtNode),
                    edges = listOf(
                        ArcEdge(id = "inspection-check", sourceId = sourceFq, targetId = targetFq, kind = edgeKind)
                    )
                )

                val violations = ArcRuleEngine.evaluate(miniGraph)
                for (v in violations) {
                    holder.registerProblem(
                        element,
                        "⚡ ArcDrift [${v.ruleId}]: ${v.message}",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                    )
                }
            }
        }
    }
}
