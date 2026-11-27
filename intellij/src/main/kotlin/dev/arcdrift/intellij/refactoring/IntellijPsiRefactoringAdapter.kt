package dev.arcdrift.intellij.refactoring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.rename.RenameProcessor
import dev.arcdrift.core.refactoring.InMemoryGraphRefactoringAdapter
import dev.arcdrift.core.refactoring.RefactoringCommand
import dev.arcdrift.core.refactoring.RefactoringPort
import dev.arcdrift.core.refactoring.RefactoringResult
import dev.arcdrift.intellij.psi.runPsiSafely
import dev.arcdrift.intellij.ui.IdeSourceNavigator
import java.util.concurrent.CancellationException

/**
 * Live IntelliJ Platform PSI implementation of [RefactoringPort] (Ports & Adapters pattern).
 * Executes deterministic code refactorings via `WriteCommandAction` and `RenameProcessor`
 * with full Undo/Redo atomicity and reference updating.
 */
class IntellijPsiRefactoringAdapter(
    private val project: Project,
    private val fallbackGraphAdapterProvider: () -> InMemoryGraphRefactoringAdapter
) : RefactoringPort {

    private fun isValidJavaIdentifier(ident: String): Boolean {
        if (ident.isBlank()) return false
        return ReadAction.compute<Boolean, RuntimeException> {
            PsiNameHelper.getInstance(project).isIdentifier(ident)
        }
    }

    private fun isValidQualifiedName(fqName: String): Boolean {
        val trimmed = fqName.trim()
        if (trimmed.isEmpty()) return false
        return ReadAction.compute<Boolean, RuntimeException> {
            PsiNameHelper.getInstance(project).isQualifiedName(trimmed)
        }
    }

    private fun executeDiskFallbackAndSync(command: RefactoringCommand): RefactoringResult {
        IdeSourceNavigator.flushUnsavedEditorDocuments(project)
        val res = fallbackGraphAdapterProvider().execute(command, previewOnly = false)
        if (res.success && res.touchedFiles.isNotEmpty()) {
            IdeSourceNavigator.syncDiskFilesIntoOpenDocuments(project, res.touchedFiles)
            IdeSourceNavigator.refreshProjectVfsAndExplorer(project, res.touchedFiles, openPrimaryFile = false)
        }
        return res
    }

    override fun execute(command: RefactoringCommand, previewOnly: Boolean): RefactoringResult {
        if (previewOnly) {
            return fallbackGraphAdapterProvider().execute(command, previewOnly = true)
        }

        return try {
            when (command) {
                is RefactoringCommand.RenameSymbol -> executePsiRename(command)
                is RefactoringCommand.AddDependency -> executePsiAddDependency(command)
                is RefactoringCommand.CreateNode -> executePsiCreateClassOrInterface(command)
                is RefactoringCommand.ExtractInterface -> executePsiExtractInterface(command)
                is RefactoringCommand.MoveClassOrPackage -> executeDiskFallbackAndSync(command)
            }
        } catch (pce: ProcessCanceledException) {
            throw pce
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            RefactoringResult(
                success = false,
                summary = "PSI refactoring failed: ${e.message}",
                errorMessage = e.message
            )
        }
    }

    private fun executePsiRename(cmd: RefactoringCommand.RenameSymbol): RefactoringResult {
        if (!isValidJavaIdentifier(cmd.newSimpleName)) {
            return RefactoringResult(
                success = false,
                summary = "Invalid identifier '${cmd.newSimpleName}' for RENAME_SYMBOL.",
                errorMessage = "Invalid identifier '${cmd.newSimpleName}'."
            )
        }
        if (findJavaPsiClass(cmd.targetNodeId) == null) {
            return executeDiskFallbackAndSync(cmd)
        }

        val prefix = cmd.targetNodeId.substringBeforeLast('.', "")
        val newFqName = if (prefix.isNotEmpty()) "$prefix.${cmd.newSimpleName}" else cmd.newSimpleName
        var renamed = false
        var touchedPath: String? = null
        val action = Runnable {
            val liveClass = findJavaPsiClass(cmd.targetNodeId) ?: return@Runnable
            RenameProcessor(project, liveClass, cmd.newSimpleName, false, false).run()
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            renamed = true
            val postRenameClass = if (liveClass.isValid) liveClass else findJavaPsiClass(newFqName)
            touchedPath = postRenameClass?.containingFile?.virtualFile?.path
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            action.run()
        } else {
            ApplicationManager.getApplication().invokeAndWait(action)
        }
        if (!renamed) {
            return executeDiskFallbackAndSync(cmd)
        }
        fallbackGraphAdapterProvider().rescanFromDiskIfAvailable()
        return RefactoringResult(
            success = true,
            summary = "Renamed PSI class '${cmd.targetNodeId}' to '${cmd.newSimpleName}' across project.",
            touchedFiles = listOfNotNull(touchedPath)
        )
    }

    private fun executePsiAddDependency(cmd: RefactoringCommand.AddDependency): RefactoringResult {
        if (!isValidQualifiedName(cmd.targetTypeFqName)) {
            return RefactoringResult(
                success = false,
                summary = "Invalid target type '${cmd.targetTypeFqName}' for ADD_DEPENDENCY.",
                errorMessage = "Invalid target type '${cmd.targetTypeFqName}'."
            )
        }
        val initialClass = findJavaPsiClass(cmd.sourceClassId)
            ?: return executeDiskFallbackAndSync(cmd)

        val isLocalDiskFile = ReadAction.compute<Boolean, RuntimeException> {
            initialClass.containingFile?.virtualFile?.isInLocalFileSystem == true
        }
        if (cmd.relationKind.equals("CONSTRUCTOR_INJECT", ignoreCase = true) && isLocalDiskFile) {
            return executeDiskFallbackAndSync(cmd)
        }

        var modified = false
        var touchedPath: String? = null
        val action = Runnable {
            WriteCommandAction.runWriteCommandAction(project, "ArcDrift: Add Dependency", null, {
                val sourceClass = findJavaPsiClass(cmd.sourceClassId) ?: return@runWriteCommandAction
                val factory = JavaPsiFacade.getElementFactory(project)
                val scope = GlobalSearchScope.allScope(project)
                val targetSimple = cmd.targetTypeFqName.substringAfterLast('.')
                when (cmd.relationKind.uppercase()) {
                    "IMPLEMENTS" -> {
                        val targetList = if (sourceClass.isInterface) sourceClass.extendsList else sourceClass.implementsList
                        val alreadyPresent = targetList?.referenceElements?.any {
                            it.qualifiedName == cmd.targetTypeFqName || it.referenceName == targetSimple
                        } == true
                        if (!alreadyPresent) {
                            val ref = factory.createReferenceFromText(cmd.targetTypeFqName, sourceClass)
                            targetList?.add(ref)
                        }
                    }
                    "EXTENDS" -> {
                        val alreadyPresent = sourceClass.extendsList?.referenceElements?.any {
                            it.qualifiedName == cmd.targetTypeFqName || it.referenceName == targetSimple
                        } == true
                        if (!alreadyPresent) {
                            val ref = factory.createReferenceFromText(cmd.targetTypeFqName, sourceClass)
                            sourceClass.extendsList?.add(ref)
                        }
                    }
                    else -> {
                        val rawFieldName = targetSimple.replaceFirstChar { it.lowercaseChar() }
                        val fieldName = if (isValidJavaIdentifier(rawFieldName)) rawFieldName else "${rawFieldName}Dep"
                        if (sourceClass.findFieldByName(fieldName, false) == null) {
                            val fieldType = factory.createTypeByFQClassName(cmd.targetTypeFqName, scope)
                            val field = factory.createField(fieldName, fieldType)
                            sourceClass.add(field)
                        }
                    }
                }
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(sourceClass)
                CodeStyleManager.getInstance(project).reformat(sourceClass)
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
                modified = true
                if (sourceClass.isValid) {
                    touchedPath = sourceClass.containingFile?.virtualFile?.path
                }
            })
        }
        if (ApplicationManager.getApplication().isDispatchThread) action.run()
        else ApplicationManager.getApplication().invokeAndWait(action)

        if (!modified) {
            return executeDiskFallbackAndSync(cmd)
        }
        fallbackGraphAdapterProvider().rescanFromDiskIfAvailable()
        return RefactoringResult(
            success = true,
            summary = "Added ${cmd.relationKind} '${cmd.targetTypeFqName}' to '${cmd.sourceClassId}'.",
            touchedFiles = listOfNotNull(touchedPath)
        )
    }

    private fun executePsiCreateClassOrInterface(cmd: RefactoringCommand.CreateNode): RefactoringResult {
        val res = executeDiskFallbackAndSync(cmd)
        if (!res.success) return res
        // If project has no disk root (e.g. light in-memory Java PSI test fixture), also materialize via JavaPsiFacade
        val baseDir = project.basePath?.let { java.io.File(it) }
        if (baseDir == null || !baseDir.exists()) {
            runPsiSafely {
                val action = Runnable {
                    val pkgName = cmd.fqName.substringBeforeLast('.', "")
                    val pkg = JavaPsiFacade.getInstance(project).findPackage(pkgName)
                    val dir = pkg?.directories?.firstOrNull() ?: return@Runnable
                    WriteCommandAction.runWriteCommandAction(project, "ArcDrift: Create Component", null, {
                        val simpleName = cmd.fqName.substringAfterLast('.')
                        val factory = JavaPsiFacade.getElementFactory(project)
                        val created = if (cmd.kind.equals("INTERFACE", ignoreCase = true)) {
                            factory.createInterface(simpleName)
                        } else {
                            factory.createClass(simpleName)
                        }
                        dir.add(created)
                    })
                }
                if (ApplicationManager.getApplication().isDispatchThread) action.run()
                else ApplicationManager.getApplication().invokeAndWait(action)
            }
        }
        return res
    }

    private fun executePsiExtractInterface(cmd: RefactoringCommand.ExtractInterface): RefactoringResult {
        if (!isValidJavaIdentifier(cmd.interfaceSimpleName) ||
            cmd.targetPackage.isBlank() ||
            !isValidQualifiedName(cmd.targetPackage)
        ) {
            return RefactoringResult(
                success = false,
                summary = "Invalid interface name '${cmd.interfaceSimpleName}' or target package '${cmd.targetPackage}'.",
                errorMessage = "INVALID_IDENTIFIER"
            )
        }
        val sourceClass = findJavaPsiClass(cmd.sourceClassId)
        val isLocalDiskFile = ReadAction.compute<Boolean, RuntimeException> {
            sourceClass?.containingFile?.virtualFile?.isInLocalFileSystem == true
        }
        val baseDir = project.basePath?.let { java.io.File(it) }
        if (baseDir != null && baseDir.exists() && (sourceClass == null || isLocalDiskFile)) {
            return executeDiskFallbackAndSync(cmd)
        }
        if (sourceClass == null) {
            return executeDiskFallbackAndSync(cmd)
        }

        var extracted = false
        val psiResult = runPsiSafely {
            val action = Runnable {
                WriteCommandAction.runWriteCommandAction(project, "ArcDrift: Extract Interface", null, {
                    val liveSource = findJavaPsiClass(cmd.sourceClassId) ?: return@runWriteCommandAction
                    val targetDir = JavaPsiFacade.getInstance(project)
                        .findPackage(cmd.targetPackage)?.directories?.firstOrNull()
                        ?: liveSource.containingFile?.containingDirectory
                        ?: return@runWriteCommandAction
                    val factory = JavaPsiFacade.getElementFactory(project)
                    val ifaceFqName = "${cmd.targetPackage}.${cmd.interfaceSimpleName}"
                    val existingIface = findJavaPsiClass(ifaceFqName)
                    if (existingIface == null) {
                        val createdIface = factory.createInterface(cmd.interfaceSimpleName)
                        for (method in liveSource.methods) {
                            if (!method.isConstructor &&
                                !method.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE) &&
                                !method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) &&
                                (cmd.memberNames.isEmpty() || method.name in cmd.memberNames)
                            ) {
                                val sig = factory.createMethodFromText(
                                    "${method.returnType?.presentableText ?: "void"} ${method.name}${method.parameterList.text};",
                                    createdIface
                                )
                                createdIface.add(sig)
                            }
                        }
                        targetDir.add(createdIface)
                    }
                    val alreadyImpl = liveSource.implementsList?.referenceElements?.any {
                        it.qualifiedName == ifaceFqName || it.referenceName == cmd.interfaceSimpleName
                    } == true
                    if (!alreadyImpl) {
                        val ref = factory.createReferenceFromText(ifaceFqName, liveSource)
                        liveSource.implementsList?.add(ref)
                    }
                    JavaCodeStyleManager.getInstance(project).shortenClassReferences(liveSource)
                    extracted = true
                })
            }
            if (ApplicationManager.getApplication().isDispatchThread) action.run()
            else ApplicationManager.getApplication().invokeAndWait(action)
        }

        if (baseDir == null || !baseDir.exists()) {
            return executeDiskFallbackAndSync(cmd)
        }
        if (psiResult.isFailure || !extracted) {
            val reason = psiResult.exceptionOrNull()?.message ?: "Target PSI class could not be modified"
            return RefactoringResult(
                success = false,
                summary = "ExtractInterface failed: $reason",
                errorMessage = "REFACTORING_FAILED"
            )
        }
        val touchedPath = ReadAction.compute<String?, RuntimeException> {
            findJavaPsiClass(cmd.sourceClassId)?.containingFile?.virtualFile?.path
        }
        fallbackGraphAdapterProvider().rescanFromDiskIfAvailable()
        return RefactoringResult(
            success = true,
            summary = "Extracted interface '${cmd.interfaceSimpleName}' (${cmd.targetPackage}.${cmd.interfaceSimpleName}) from '${cmd.sourceClassId}'.",
            touchedFiles = listOfNotNull(touchedPath)
        )
    }

    /**
     * Returns a physical Java [PsiClass] (excluding read-only Kotlin `KtLightClass` wrappers).
     */
    private fun findJavaPsiClass(fqName: String): PsiClass? {
        return ReadAction.compute<PsiClass?, RuntimeException> {
            val cls = JavaPsiFacade.getInstance(project).findClass(fqName, GlobalSearchScope.projectScope(project))
            if (cls != null && cls.containingFile?.name?.endsWith(".java", ignoreCase = true) == true) {
                cls
            } else {
                null
            }
        }
    }
}
