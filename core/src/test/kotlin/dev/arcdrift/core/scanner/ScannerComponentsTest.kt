package dev.arcdrift.core.scanner

import dev.arcdrift.core.ast.JvmParsedClassAst
import dev.arcdrift.core.ast.JvmParsedFileAst
import dev.arcdrift.core.model.ArcEdge
import dev.arcdrift.core.model.ArcGraph
import dev.arcdrift.core.model.ArcNode
import dev.arcdrift.core.model.C4Level
import dev.arcdrift.core.model.ContractEndpoint
import dev.arcdrift.core.model.EdgeKind
import dev.arcdrift.core.model.NodeKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerComponentsTest {

    @Test
    fun `OpenApiAndTypeSpecExtractor extracts YAML operations, allOf inheritance, and TypeSpec models`() {
        val tempDir = Files.createTempDirectory("arcdrift-openapi-test").toFile()
        try {
            tempDir.resolve("openapi.yaml").writeText(
                """
                openapi: 3.0.3
                paths:
                  /api/v1/orders/{id}:
                    get:
                      operationId: getOrderById
                components:
                  schemas:
                    BaseEntity:
                      properties:
                        id:
                          type: string
                    Order:
                      allOf:
                        - ${'$'}ref: '#/components/schemas/BaseEntity'
                      properties:
                        total:
                          type: number
                """.trimIndent(),
                Charsets.UTF_8
            )

            tempDir.resolve("api.tsp").writeText(
                """
                model AuditFields {
                  createdAt: utcDateTime;
                }
                model ItemModel extends AuditFields {
                  name: string;
                }
                @route("/items")
                interface ItemsApi {
                  @get
                  @route("/{id}")
                  @operationId("readItem")
                  readItem(): ItemModel;
                }
                """.trimIndent(),
                Charsets.UTF_8
            )

            val result = OpenApiAndTypeSpecExtractor.extract(tempDir, emptySet())
            assertEquals("GET" to "/api/v1/orders/{id}", result.operations["getOrderById"])
            assertEquals("GET" to "/items/{id}", result.operations["readItem"])
            assertEquals(mapOf("id" to "string", "total" to "number"), result.schemas["Order"])
            assertEquals(mapOf("id" to "string", "total" to "number"), result.schemas["OrderDto"])
            assertEquals(mapOf("createdAt" to "utcDateTime", "name" to "string"), result.schemas["ItemModel"])

            // Verify 4-space indented YAML and inline allOf: [ { $ref: '...' } ]
            tempDir.resolve("openapi-4space.yaml").writeText(
                """
                openapi: 3.1.0
                paths:
                    /api/v2/invoices/{id}:
                        post:
                            operationId: createInvoiceV2
                components:
                    schemas:
                        InvoiceV2:
                            allOf: [{ ${'$'}ref: '#/components/schemas/BaseEntity' }]
                            properties:
                                amountCents:
                                    type: integer
                """.trimIndent(),
                Charsets.UTF_8
            )
            val result4 = OpenApiAndTypeSpecExtractor.extract(tempDir, emptySet())
            assertEquals("POST" to "/api/v2/invoices/{id}", result4.operations["createInvoiceV2"])
            assertEquals(mapOf("id" to "string", "amountCents" to "integer"), result4.schemas["InvoiceV2"])
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `PolyglotRepoScanner classifies pure FastAPI router module as REST_CONTROLLER`() {
        val tempDir = Files.createTempDirectory("arcdrift-fastapi-kind").toFile()
        try {
            val pyRouter = tempDir.resolve("backend/app/api/routes/items.py").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    from fastapi import APIRouter
                    router = APIRouter(prefix="/items")

                    @router.get("/{id}")
                    def get_item(id: str):
                        return {"id": id}
                    """.trimIndent()
                )
            }
            assertTrue(pyRouter.exists())
            val graph = PolyglotRepoScanner.scanRepository(tempDir)
            val routerNode = graph.nodes.first { it.label == "items.py" }
            assertEquals(NodeKind.REST_CONTROLLER, routerNode.kind)
            assertTrue("FastAPI Router" in routerNode.stereotypes)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `RepoContractEnricher unwraps openapi-ts Data and status wrappers and enriches Python prefixes`() {
        val tsTypes = mapOf(
            "GetItemsResponse" to mapOf("200" to "PageItemDto"),
            "PageItemDto" to mapOf("content" to "List<ItemDto>", "totalElements" to "number", "totalPages" to "number"),
            "ItemDto" to mapOf("id" to "string", "title" to "string"),
            "CreateItemData" to mapOf("body" to "ItemCreate", "path" to "never", "url" to "/items"),
            "ItemCreate" to mapOf("title" to "string"),
            "IUser" to mapOf("email" to "string")
        )

        val (respName, respFields) = RepoContractEnricher.resolveTsDtoAndFields("GetItemsResponse", tsTypes)
        assertEquals("ItemDto", respName)
        assertEquals(mapOf("id" to "string", "title" to "string"), respFields)

        val (restName, restFields) = RepoContractEnricher.resolveTsDtoAndFields("RestUser", tsTypes)
        assertEquals("IUser", restName)
        assertEquals(mapOf("email" to "string"), restFields)

        val (reqName, reqFields) = RepoContractEnricher.resolveTsRequestDtoAndFields("CreateItemData", emptyMap(), tsTypes)
        assertEquals("ItemCreate", reqName)
        assertEquals(mapOf("title" to "string"), reqFields)

        val nodesById = linkedMapOf(
            "ts.web.itemsClient" to ArcNode(
                id = "ts.web.itemsClient",
                label = "itemsClient.ts",
                c4Level = C4Level.L3_COMPONENT,
                kind = NodeKind.TS_CLIENT,
                contracts = emptyList()
            ),
            "py.app.routes" to ArcNode(
                id = "py.app.routes",
                label = "routes.py",
                c4Level = C4Level.L3_COMPONENT,
                kind = NodeKind.PYTHON_CONSUMER,
                contracts = listOf(
                    ContractEndpoint(
                        protocol = "REST",
                        methodOrAction = "POST",
                        pathOrTopic = "/items",
                        role = "PRODUCER",
                        requestDto = "ItemCreate"
                    )
                )
            )
        )

        RepoContractEnricher.enrichPolyglotContracts(
            nodesById = nodesById,
            tsTypeDefinitions = tsTypes,
            tsSdkMethods = emptyMap(),
            tsSdkCallsByNodeId = mapOf("ts.web.itemsClient" to listOf("ItemsService" to "createItem")),
            pyModelDefinitions = mapOf("ItemCreate" to mapOf("title" to "str")),
            pyApiPrefixes = mapOf("API_V1_STR" to "/api/v1"),
            openApiOperations = mapOf("createItem" to ("POST" to "/api/v1/items")),
            implementedJvmMethodNames = setOf("createItem")
        )

        val tsContracts = nodesById.getValue("ts.web.itemsClient").contracts
        assertEquals(1, tsContracts.size)
        assertEquals("/api/v1/items", tsContracts.first().pathOrTopic)

        val pyContract = nodesById.getValue("py.app.routes").contracts.first()
        assertEquals("/api/v1/items", pyContract.pathOrTopic)
        assertEquals(mapOf("title" to "str"), pyContract.requestDtoFields)
    }

    @Test
    fun `RepoEdgeBuilder resolves JVM, TS, Python, and ghost edges and deduplicates supertype DEPENDS_ON`() {
        val portClass = JvmParsedClassAst(
            fqId = "com.acme.OrderPort",
            simpleName = "OrderPort",
            pkg = "com.acme",
            kind = NodeKind.INTERFACE,
            stereotypes = emptyList(),
            annotations = emptyList(),
            fields = emptyMap(),
            members = emptyList(),
            methods = emptyList(),
            inlineRoutes = emptyList(),
            superTypeSimpleNames = emptySet(),
            referencedSimpleNames = emptySet(),
            referencedQualifiedNames = emptySet(),
            startLine = 1,
            endLine = 5
        )
        val implClass = JvmParsedClassAst(
            fqId = "com.acme.OrderService",
            simpleName = "OrderService",
            pkg = "com.acme",
            kind = NodeKind.CLASS,
            stereotypes = emptyList(),
            annotations = emptyList(),
            fields = emptyMap(),
            members = emptyList(),
            methods = emptyList(),
            inlineRoutes = emptyList(),
            superTypeSimpleNames = setOf("OrderPort"),
            referencedSimpleNames = setOf("OrderPort"),
            referencedQualifiedNames = emptySet(),
            startLine = 1,
            endLine = 10
        )
        val records = listOf(
            ScannedJvmClassRecord(
                JvmParsedFileAst(
                    pkg = "com.acme",
                    explicitImports = emptyList(),
                    wildcardImports = emptyList(),
                    importAliases = emptyMap(),
                    classes = listOf(portClass)
                ),
                portClass,
                "OrderPort.kt",
                "kotlin"
            ),
            ScannedJvmClassRecord(
                JvmParsedFileAst(
                    pkg = "com.acme",
                    explicitImports = listOf("com.acme.OrderPort"),
                    wildcardImports = emptyList(),
                    importAliases = emptyMap(),
                    classes = listOf(implClass)
                ),
                implClass,
                "OrderService.kt",
                "kotlin"
            )
        )
        val nodesById = linkedMapOf(
            "com.acme.OrderPort" to ArcNode("com.acme.OrderPort", "OrderPort", C4Level.L3_COMPONENT, NodeKind.INTERFACE),
            "com.acme.OrderService" to ArcNode("com.acme.OrderService", "OrderService", C4Level.L3_COMPONENT, NodeKind.CLASS),
            "ts.web.app" to ArcNode("ts.web.app", "app.ts", C4Level.L3_COMPONENT, NodeKind.TS_CLIENT),
            "ts.web.client" to ArcNode("ts.web.client", "client.ts", C4Level.L3_COMPONENT, NodeKind.TS_CLIENT),
            "py.app.main" to ArcNode("py.app.main", "main.py", C4Level.L3_COMPONENT, NodeKind.PYTHON_CONSUMER),
            "py.app.models" to ArcNode("py.app.models", "models.py", C4Level.L3_COMPONENT, NodeKind.PYTHON_CONSUMER),
            "spec.Planned" to ArcNode("spec.Planned", "Planned", C4Level.L3_COMPONENT, NodeKind.GHOST_SPEC)
        )
        val specGraph = ArcGraph(
            projectName = "test",
            nodes = listOf(
                ArcNode("com.acme.OrderService", "OrderService", C4Level.L3_COMPONENT, NodeKind.CLASS, layoutX = 120.0, layoutY = 240.0)
            ),
            edges = listOf(
                ArcEdge("spec-1", "com.acme.OrderService", "spec.Planned", EdgeKind.DEPENDS_ON)
            )
        )

        val edges = RepoEdgeBuilder.buildEdges(
            scannedJvmClasses = records,
            tsImportsByNodeId = mapOf("ts.web.app" to ("web/app.ts" to listOf("./client"))),
            pyImportsByNodeId = mapOf("py.app.main" to listOf("app.models")),
            nodesById = nodesById,
            specGraph = specGraph
        )

        // IMPLEMENTS edge should suppress redundant DEPENDS_ON between OrderService -> OrderPort
        assertTrue(edges.any { it.sourceId == "com.acme.OrderService" && it.targetId == "com.acme.OrderPort" && it.kind == EdgeKind.IMPLEMENTS })
        assertFalse(edges.any { it.sourceId == "com.acme.OrderService" && it.targetId == "com.acme.OrderPort" && it.kind == EdgeKind.DEPENDS_ON })
        assertTrue(edges.any { it.sourceId == "ts.web.app" && it.targetId == "ts.web.client" })
        assertTrue(edges.any { it.sourceId == "py.app.main" && it.targetId == "py.app.models" })
        assertTrue(edges.any { it.sourceId == "com.acme.OrderService" && it.targetId == "spec.Planned" })
        assertEquals(120.0, nodesById.getValue("com.acme.OrderService").layoutX)
        assertNull(nodesById.getValue("com.acme.OrderPort").layoutX)
    }

    @Test
    fun `OpenAPI array properties, inline YAML comments, property named properties, and cross-file TS and Python inheritance work end-to-end`() {
        val tempDir = kotlin.io.path.createTempDirectory("arcdrift-openapi-array-inheritance").toFile()
        try {
            File(tempDir, "openapi.yaml").writeText(
                """
                openapi: 3.0.3 # root comment
                paths: # paths comment
                  /api/v1/orders: # endpoint comment
                    get: # verb comment
                      operationId: listOrders # op comment
                components:
                  schemas: # schemas comment
                    OrderItemDto:
                      type: object
                      properties:
                        sku:
                          type: string
                    OrderListDto:
                      type: object
                      properties: # properties comment
                        items:
                          type: array
                          items:
                            ${'$'}ref: '#/components/schemas/OrderItemDto'
                        tags:
                          type: array
                          items:
                            type: string
                        properties:
                          type: string
                """.trimIndent()
            )
            File(tempDir, "main.tsp").writeText(
                """
                model TspOrder {
                  skus: string[];
                  count: int32;
                }
                """.trimIndent()
            )
            val oaResult = OpenApiAndTypeSpecExtractor.extract(tempDir, emptySet())
            assertEquals("GET" to "/api/v1/orders", oaResult.operations["listOrders"])
            val orderListFields = requireNotNull(oaResult.schemas["OrderListDto"])
            assertEquals("OrderItemDto[]", orderListFields["items"], "OpenAPI array of \$ref must preserve [] suffix")
            assertEquals("string[]", orderListFields["tags"], "OpenAPI array of primitive must preserve [] suffix")
            assertEquals("string", orderListFields["properties"], "A property named 'properties' must not reset propertiesIndent")
            val tspFields = requireNotNull(oaResult.schemas["TspOrder"])
            assertEquals("string[]", tspFields["skus"], "TypeSpec array property must preserve [] suffix")

            // Cross-file and bottom-up TypeScript and Python inheritance
            File(tempDir, "web/baseTypes.ts").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    // Child declared before Parent in same file
                    export interface LocalChildDto extends LocalParentDto {
                        childField: number;
                    }
                    export interface LocalParentDto {
                        parentField: string;
                    }
                    export interface SharedBaseDto {
                        id: string;
                        tags: string[];
                    }
                    """.trimIndent()
                )
            }
            File(tempDir, "web/orderClient.ts").writeText(
                """
                import { SharedBaseDto } from './baseTypes';
                export interface CrossFileOrderDto extends SharedBaseDto {
                    items: string[];
                }
                export async function fetchOrders(): Promise<CrossFileOrderDto> {
                    const res = await fetch('/api/v1/orders');
                    return res.json();
                }
                """.trimIndent()
            )
            File(tempDir, "py/base_models.py").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    from pydantic import BaseModel
                    class LocalPyChild(LocalPyParent):
                        extra: int
                    class LocalPyParent(BaseModel):
                        base_id: str
                    class SharedPyBase(BaseModel):
                        id: str
                    """.trimIndent()
                )
            }
            File(tempDir, "py/consumer.py").writeText(
                """
                import requests
                from base_models import SharedPyBase
                class PyOrderModel(SharedPyBase):
                    total: int
                def get_orders() -> PyOrderModel:
                    return requests.get('/api/v1/orders').json()
                """.trimIndent()
            )

            val tsParsed = dev.arcdrift.core.ast.TypeScriptAstParser.parseTypeScriptFile(File(tempDir, "web/baseTypes.ts").readText())
            assertEquals(mapOf("parentField" to "string", "childField" to "number"), tsParsed.types["LocalChildDto"])

            val pyParsed = dev.arcdrift.core.ast.PythonAstParser.parsePythonFile(File(tempDir, "py/base_models.py").readText())
            assertEquals(mapOf("base_id" to "str", "extra" to "int"), pyParsed.models["LocalPyChild"])

            val scanned = PolyglotRepoScanner.scanRepository(tempDir)
            val tsClient = requireNotNull(scanned.nodeMap()["ts.web.orderClient"])
            assertEquals(
                mapOf("id" to "string", "tags" to "string[]", "items" to "string[]"),
                tsClient.contracts.first().dtoFields
            )
            val pyConsumer = requireNotNull(scanned.nodeMap()["py.py.consumer"])
            assertEquals(
                mapOf("id" to "str", "total" to "int"),
                pyConsumer.contracts.first().dtoFields
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
