package dev.arcdrift.core.refactoring

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Serializable refactoring commands (Ports & Adapters pattern)
 * decoupled from IntelliJ Platform UI/PSI dependencies.
 */
@Serializable
sealed class RefactoringCommand {
    @Serializable
    @SerialName("RENAME_SYMBOL")
    data class RenameSymbol(
        val targetNodeId: String,
        val newSimpleName: String
    ) : RefactoringCommand()

    @Serializable
    @SerialName("MOVE_CLASS_OR_PACKAGE")
    data class MoveClassOrPackage(
        val targetNodeId: String,
        val destinationPackage: String
    ) : RefactoringCommand()

    @Serializable
    @SerialName("EXTRACT_INTERFACE")
    data class ExtractInterface(
        val sourceClassId: String,
        val interfaceSimpleName: String,
        val targetPackage: String,
        val memberNames: List<String> = emptyList()
    ) : RefactoringCommand()

    @Serializable
    @SerialName("CREATE_NODE")
    data class CreateNode(
        val fqName: String,
        val kind: String = "CLASS", // "CLASS" | "INTERFACE" | "DATA_CLASS" | "ENUM" | "GHOST_SPEC"
        val stereotypes: List<String> = emptyList()
    ) : RefactoringCommand()

    @Serializable
    @SerialName("ADD_DEPENDENCY")
    data class AddDependency(
        val sourceClassId: String,
        val targetTypeFqName: String,
        val relationKind: String = "DEPENDS_ON" // "DEPENDS_ON" | "IMPLEMENTS" | "EXTENDS" | "CONSTRUCTOR_INJECT"
    ) : RefactoringCommand()
}

/**
 * Result of executing or previewing a [RefactoringCommand].
 */
@Serializable
data class RefactoringResult(
    val success: Boolean,
    val summary: String,
    val touchedFiles: List<String> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Hexagonal Port implemented by `IntellijPsiRefactoringAdapter` (in IDE)
 * and `InMemoryGraphRefactoringAdapter` (in headless CLI / tests).
 */
interface RefactoringPort {
    fun execute(command: RefactoringCommand, previewOnly: Boolean = false): RefactoringResult
}
