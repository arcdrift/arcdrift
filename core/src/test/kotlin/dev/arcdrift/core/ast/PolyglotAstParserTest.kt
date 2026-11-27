package dev.arcdrift.core.ast

import dev.arcdrift.core.model.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolyglotAstParserTest {

    @Test
    fun `SourceLexer and KotlinAstParser ignore nested block comments and fake imports inside string literals`() {
        val code = """
            package com.acme.controller
            /*
               Outer comment
               /* Nested Kotlin comment with fake import:
                  import com.acme.repo.FakeRepo
               */
               Still in outer comment: FakeRepo()
            */
            import com.acme.service.OrderService

            @RestController
            @RequestMapping(path = ["/api/v1/orders"], produces = ["application/json"])
            class OrderController(
                @Qualifier("primary") val service: OrderService,
                val maxRetries: Int = maxOf(1, 3)
            ) : BaseController<List<String>>(), Auditable {
                val fakeCodeInString = ""${'"'}
                    import com.acme.repo.OrderRepository
                    class InjectedFakeClass {
                        val repo = com.acme.repo.OrderRepository()
                    }
                ""${'"'}

                @GetMapping(value = "/{id}")
                fun getOrder(id: String) = ResponseEntity.ok(OrderDto(id, 100L))
            }
        """.trimIndent()

        val ast = KotlinAstParser.parseKotlinFile(code)
        assertEquals("com.acme.controller", ast.pkg)
        assertEquals(listOf("com.acme.service.OrderService"), ast.explicitImports)
        assertEquals(1, ast.classes.size, "Fake class inside raw string literal must not be parsed as a class")

        val ctrl = ast.classes.first()
        assertEquals("com.acme.controller.OrderController", ctrl.fqId)
        assertEquals(NodeKind.REST_CONTROLLER, ctrl.kind)
        assertFalse("FakeRepo" in ctrl.referencedSimpleNames, "Nested comment references must be stripped")
        assertFalse("com.acme.repo.OrderRepository" in ctrl.referencedQualifiedNames, "String literal FQN must be ignored")
        assertTrue("OrderService" in ctrl.referencedSimpleNames)
        assertTrue("BaseController" in ctrl.referencedSimpleNames)
        assertTrue("Auditable" in ctrl.referencedSimpleNames)

        val getOrderMethod = ctrl.methods.find { it.name == "getOrder" }
        assertNotNull(getOrderMethod)
        assertEquals("OrderDto", getOrderMethod.inferredReturnTypeName)
    }

    @Test
    fun `JavaAstExtractor parses Java 21 records, annotations, generics, and method-body AST expressions`() {
        val javaSource = """
            package com.acme.billing;

            import com.acme.audit.*;
            import org.springframework.web.bind.annotation.*;

            public record InvoiceRecord(String invoiceId, long amountCents) {}

            @RestController
            @RequestMapping("/v1/invoices")
            public class InvoiceController {
                @PostMapping("/{id}")
                public ResponseEntity<List<InvoiceRecord>> createInvoice(@RequestBody InvoiceRecord req) {
                    var audit = new com.acme.audit.AuditClient();
                    audit.recordEvent("created");
                    return null;
                }
            }
        """.trimIndent()

        val ast = JavaAstExtractor.parseJavaFile(javaSource)
        assertEquals("com.acme.billing", ast.pkg)
        assertEquals(listOf("com.acme.audit", "org.springframework.web.bind.annotation"), ast.wildcardImports)
        assertEquals(2, ast.classes.size)

        val recordAst = ast.classes.first { it.simpleName == "InvoiceRecord" }
        assertEquals(NodeKind.DATA_CLASS, recordAst.kind)
        assertEquals(mapOf("invoiceId" to "String", "amountCents" to "long"), recordAst.fields)

        val ctrlAst = ast.classes.first { it.simpleName == "InvoiceController" }
        assertEquals(NodeKind.REST_CONTROLLER, ctrlAst.kind)
        assertTrue("com.acme.audit.AuditClient" in ctrlAst.referencedQualifiedNames)
    }

    @Test
    fun `TypeScriptAstParser and PythonAstParser extract AST contracts and DTO schemas`() {
        val tsCode = """
            import { http } from './http';
            // Commented out interface Fake { bad: string; }
            export type InvoicePayload = {
                invoiceId: string;
                amountCents: number;
            };
            export async function patchInvoice(id: string): Promise<InvoicePayload> {
                const res = await fetch(`${'$'}{API_URL}/v1/invoices/${'$'}{id}`, { method: 'patch' });
                return res.json();
            }
        """.trimIndent()

        val tsAst = TypeScriptAstParser.parseTypeScriptFile(tsCode)
        assertEquals(listOf("./http"), tsAst.importPaths)
        assertEquals(setOf("InvoicePayload"), tsAst.types.keys)
        assertEquals(1, tsAst.contracts.size)
        val tsContract = tsAst.contracts.first()
        assertEquals("PATCH", tsContract.methodOrAction)
        assertEquals("InvoicePayload", tsContract.responseDto)
        assertEquals(mapOf("invoiceId" to "string", "amountCents" to "number"), tsContract.dtoFields)

        val pyCode = """
            from dataclasses import dataclass

            # class CommentedOut(BaseModel):
            #     badField: str

            @dataclass
            class InvoiceModel:
                invoiceId: str
                amountCents: int

            @router.post("/v1/invoices", response_model=InvoiceModel)
            def create_invoice():
                return httpx.get(f"{BASE_URL}/v1/invoices/123")
        """.trimIndent()

        val pyAst = PythonAstParser.parsePythonFile(pyCode)
        assertEquals(setOf("InvoiceModel"), pyAst.models.keys)
        assertEquals(2, pyAst.contracts.size)
        assertTrue(pyAst.contracts.any { it.role == "PRODUCER" && it.methodOrAction == "POST" && it.responseDto == "InvoiceModel" })
        assertTrue(pyAst.contracts.any { it.role == "CONSUMER" && it.methodOrAction == "GET" && it.responseDto == "InvoiceModel" })
    }

    @Test
    fun `Official AST parsers capture callable references, class literals, companion objects, constructor injection, and FastAPI return types`() {
        val ktCode = """
            package com.acme.controller
            class OrderController {
                companion object {
                    val ref = OrderRepository::findOrder
                    val cls = com.acme.repo.AuditRepository::class
                }
            }
        """.trimIndent()
        val ktAst = KotlinAstParser.parseKotlinFile(ktCode).classes.single()
        assertTrue("OrderRepository" in ktAst.referencedSimpleNames)
        assertTrue("com.acme.repo.AuditRepository" in ktAst.referencedQualifiedNames)

        val javaCode = """
            package com.acme.controller;
            public class PaymentController {
                public PaymentController(PaymentService paymentService) {}
                public void run() {
                    Runnable r = PaymentRepository::flush;
                    kafkaTemplate.send("payments.topic", "event");
                    StaticHelper.execute();
                    var f = com.acme.repo.AuditRepo.INSTANCE;
                }
            }
        """.trimIndent()
        val javaAst = JavaAstExtractor.parseJavaFile(javaCode).classes.single()
        assertTrue("PaymentService" in javaAst.referencedSimpleNames)
        assertTrue("PaymentRepository" in javaAst.referencedSimpleNames)

        val pyFastApiReturnAnnotation = """
            from app.models import OrderOut
            class OrderOut:
                orderId: str
                amountCents: int

            @app.get("/v1/orders/{id}")
            async def get_order(id: str) -> OrderOut:
                return OrderOut()
        """.trimIndent()
        val pyAst = PythonAstParser.parsePythonFile(pyFastApiReturnAnnotation)
        assertEquals(listOf("app.models"), pyAst.importModules)
        assertEquals(1, pyAst.contracts.size)
        assertEquals("OrderOut", pyAst.contracts.single().responseDto)
    }

    @Test
    fun `nested classes have qualified fqIds, isolated dependencies, multi-path Spring routes, and array wire types`() {
        val ktSource = """
            package com.acme.orders
            import org.springframework.web.bind.annotation.*

            data class OrderListDto(val tags: List<String>, val counts: Set<Int>)

            @RestController
            @RequestMapping(path = ["/v1/orders", "/v2/orders"])
            class OuterController {
                @GetMapping(value = ["/list", "/all"])
                fun listOrders(): ResponseEntity<List<OrderListDto>> = TODO()

                class InnerWorker(private val repo: SecretInnerRepository) {
                    fun work() = repo.save()
                }
            }
        """.trimIndent()

        val ktAst = KotlinAstParser.parseKotlinFile(ktSource)
        val dto = ktAst.classes.first { it.simpleName == "OrderListDto" }
        assertEquals("String[]", dto.fields["tags"])
        assertEquals("Int[]", dto.fields["counts"])

        val outer = ktAst.classes.first { it.simpleName == "OuterController" }
        val inner = ktAst.classes.first { it.simpleName == "InnerWorker" }
        assertEquals("com.acme.orders.OuterController", outer.fqId)
        assertEquals("com.acme.orders.OuterController.InnerWorker", inner.fqId)
        assertFalse("SecretInnerRepository" in outer.referencedSimpleNames, "Inner class dependency must not leak to outer class")
        assertTrue("SecretInnerRepository" in inner.referencedSimpleNames)

        // Cross-product of 2 class paths x 2 method paths = 4 routes
        val classPaths = outer.annotations.first { it.name == "RequestMapping" }.allPathsOrValues()
        val methodPaths = outer.methods.first { it.name == "listOrders" }.annotations.first { it.name == "GetMapping" }.allPathsOrValues()
        val routePaths = classPaths.flatMap { c -> methodPaths.map { m -> "$c$m" } }.toSet()
        assertEquals(
            setOf("/v1/orders/list", "/v1/orders/all", "/v2/orders/list", "/v2/orders/all"),
            routePaths
        )

        val javaSource = """
            package com.acme.orders;
            import java.util.List;
            public class JavaOuter {
                private List<String> items;
                public static class JavaInner {
                    private InnerOnlyDependency dep;
                }
            }
        """.trimIndent()
        val javaAst = JavaAstExtractor.parseJavaFile(javaSource)
        val jOuter = javaAst.classes.first { it.simpleName == "JavaOuter" }
        val jInner = javaAst.classes.first { it.simpleName == "JavaInner" }
        assertEquals("com.acme.orders.JavaOuter", jOuter.fqId)
        assertEquals("com.acme.orders.JavaOuter.JavaInner", jInner.fqId)
        assertEquals("String[]", jOuter.fields["items"])
        assertFalse("InnerOnlyDependency" in jOuter.referencedSimpleNames)
        assertTrue("InnerOnlyDependency" in jInner.referencedSimpleNames)
    }

    @Test
    fun `TypeScriptAstParser handles comment tokens inside strings, axios delete config vs post body, and axios request`() {
        val tsSource = """
            // Single-line comment with /* unclosed block comment token
            const pattern = "/* not a comment */ and */ inside string";
            /* Standard block comment before declarations */
            export interface CreateReq { title: string; }
            export interface ItemResp { id: string; title: string; }

            export async function apiCalls(req: CreateReq) {
                await axios.delete<ItemResp>('/api/v1/items/1', { headers: { Authorization: 'Bearer x' } });
                await axios.post<ItemResp>('/api/v1/items', req);
                await axios.request<ItemResp>({ method: 'PUT', url: '/api/v1/items/1', data: req });
            }
        """.trimIndent()

        val tsAst = TypeScriptAstParser.parseTypeScriptFile(tsSource)
        assertEquals(setOf("CreateReq", "ItemResp"), tsAst.types.keys)
        val deleteContract = assertNotNull(tsAst.contracts.find { it.methodOrAction == "DELETE" }, "Expected DELETE in ${tsAst.contracts}")
        assertEquals(null, deleteContract.requestDto, "axios.delete 2nd argument is AxiosRequestConfig, not request body DTO")

        val postContract = assertNotNull(tsAst.contracts.find { it.methodOrAction == "POST" }, "Expected POST in ${tsAst.contracts}")
        assertEquals("CreateReq", postContract.requestDto)
        assertEquals(mapOf("title" to "string"), postContract.requestDtoFields)

        val putContract = assertNotNull(tsAst.contracts.find { it.methodOrAction == "PUT" }, "Expected PUT in ${tsAst.contracts}")
        assertEquals("/api/v1/items/1", putContract.pathOrTopic)
        assertEquals("CreateReq", putContract.requestDto)
    }

    @Test
    fun `PythonAstParser combines APIRouter prefix with route path`() {
        val pySource = """
            from fastapi import APIRouter
            from pydantic import BaseModel

            class ItemCreate(BaseModel):
                title: str

            router = APIRouter(prefix="/items", tags=["items"])

            @router.post("/", response_model=ItemCreate)
            def create_item(body: ItemCreate):
                return body
        """.trimIndent()

        val pyAst = PythonAstParser.parsePythonFile(pySource)
        assertEquals(1, pyAst.contracts.size)
        val route = pyAst.contracts.single()
        assertEquals("POST", route.methodOrAction)
        assertEquals("/items", route.pathOrTopic)
        assertEquals("ItemCreate", route.requestDto)
    }

    @Test
    fun `KotlinAstParser parses top-level Ktor routes, expression return types, import aliases, and AstTypeUnwrapper contract builders`() {
        val ktorFile = """
            package com.acme.ktor
            import com.acme.service.OrderService as OrderSvcAlias

            val defaultRoutePrefix = "/api/v1"

            fun flagEnabled() = true
            fun maxTimeoutMs() = 5000L
            fun ratio() = 0.75
            fun retryCount() = 3
            fun label() = "orders"
            fun itemsList() = listOf("a")
            fun itemsSet() = setOf("b")
            fun itemsMap() = mapOf("k" to "v")

            fun Route.orderRoutes(svc: OrderSvcAlias) {
                get("/v1/orders/{id}") {
                    call.respond(OrderDto("1"))
                }
                post("/v1/orders") {
                    call.respond("ok")
                }
            }
        """.trimIndent()

        val ast = KotlinAstParser.parseKotlinFile(ktorFile, defaultFileSimpleName = "OrderRoutesKt")
        assertEquals("com.acme.service.OrderService", ast.importAliases["OrderSvcAlias"])
        val fileCls = ast.classes.single()
        assertEquals("com.acme.ktor.OrderRoutesKt", fileCls.fqId)
        assertEquals("Boolean", fileCls.methods.first { it.name == "flagEnabled" }.inferredReturnTypeName)
        assertEquals("Long", fileCls.methods.first { it.name == "maxTimeoutMs" }.inferredReturnTypeName)
        assertEquals("Double", fileCls.methods.first { it.name == "ratio" }.inferredReturnTypeName)
        assertEquals("Int", fileCls.methods.first { it.name == "retryCount" }.inferredReturnTypeName)
        assertEquals("String", fileCls.methods.first { it.name == "label" }.inferredReturnTypeName)
        assertEquals("List<Any>", fileCls.methods.first { it.name == "itemsList" }.inferredReturnTypeName)
        assertEquals("Set<Any>", fileCls.methods.first { it.name == "itemsSet" }.inferredReturnTypeName)
        assertEquals("Map<Any, Any>", fileCls.methods.first { it.name == "itemsMap" }.inferredReturnTypeName)
        assertTrue(fileCls.inlineRoutes.any { it.methodOrAction == "GET" && it.pathOrTopic == "/v1/orders/{id}" })
        assertTrue(fileCls.inlineRoutes.any { it.methodOrAction == "POST" && it.pathOrTopic == "/v1/orders" })

        // Exercise AstTypeUnwrapper.buildContractsForClass and classifyNodeKind across verbs, Kafka, and OpenAPI fallback
        val methods = listOf(
            AstMethod("updateOrder", listOf(AstParam("body", AstTypeRef("OrderReq"), listOf(AstAnnotation("RequestBody")))), AstTypeRef("OrderDto"), annotations = listOf(AstAnnotation("PutMapping", positionalStrings = listOf("/{id}")))),
            AstMethod("deleteOrder", emptyList(), AstTypeRef("Unit"), annotations = listOf(AstAnnotation("DeleteMapping", positionalStrings = listOf("/{id}")))),
            AstMethod("patchOrder", listOf(AstParam("body", AstTypeRef("OrderReq"))), AstTypeRef("OrderDto"), annotations = listOf(AstAnnotation("PatchMapping", positionalStrings = listOf("/{id}")))),
            AstMethod("customPost", emptyList(), AstTypeRef("OrderDto"), annotations = listOf(AstAnnotation("RequestMapping", namedArgs = mapOf("method" to listOf("RequestMethod.POST")), positionalStrings = listOf("/custom")))),
            AstMethod("onOrderEvent", emptyList(), AstTypeRef("Unit"), annotations = listOf(AstAnnotation("KafkaListener", namedArgs = mapOf("topics" to listOf("orders.events"))))),
            AstMethod("openApiOp", listOf(AstParam("req", AstTypeRef("OrderReqDto"))), AstTypeRef("OrderDto"), annotations = emptyList())
        )
        val built = AstTypeUnwrapper.buildContractsForClass(
            annotations = listOf(
                AstAnnotation("RequestMapping", positionalStrings = listOf("/api/orders")),
                AstAnnotation("RabbitListener", positionalStrings = listOf("orders.queue"))
            ),
            methods = methods,
            inlineRoutes = emptyList(),
            dtoDefinitions = mapOf("OrderReq" to mapOf("amount" to "Long"), "OrderDto" to mapOf("id" to "String")),
            openApiOperations = mapOf("openApiOp" to ("POST" to "/api/orders/openapi")),
            isControllerOrApiImpl = true
        )
        assertTrue(built.any { it.methodOrAction == "PUT" && it.pathOrTopic == "/api/orders/{id}" })
        assertTrue(built.any { it.methodOrAction == "DELETE" && it.pathOrTopic == "/api/orders/{id}" })
        assertTrue(built.any { it.methodOrAction == "PATCH" && it.pathOrTopic == "/api/orders/{id}" })
        assertTrue(built.any { it.methodOrAction == "POST" && it.pathOrTopic == "/api/orders/custom" })
        assertTrue(built.any { it.protocol == "KAFKA" && it.pathOrTopic == "orders.events" })
        assertTrue(built.any { it.protocol == "KAFKA" && it.pathOrTopic == "orders.queue" })
        assertTrue(built.any { it.methodOrAction == "POST" && it.pathOrTopic == "/api/orders/openapi" })

        assertEquals(NodeKind.ENTITY, AstTypeUnwrapper.classifyNodeKind("class", listOf("@Entity"), "OrderEntity", false))
        assertEquals(NodeKind.MESSAGE_LISTENER, AstTypeUnwrapper.classifyNodeKind("class", listOf("@KafkaListener"), "OrderListener", false))
        assertEquals(NodeKind.ENUM, AstTypeUnwrapper.classifyNodeKind("enum class", emptyList(), "OrderStatus", false))
        assertEquals("OrderDto", AstTypeUnwrapper.unwrapPrimaryDtoString("ResponseEntity<List<OrderDto?>>"))

        val deepNestedKt = """
            package com.acme.events
            sealed class DomainEvent {
                sealed class OrderEvent : DomainEvent() {
                    data class OrderCreated(val orderId: String) : OrderEvent()
                }
            }
        """.trimIndent()
        val deepParsed = KotlinAstParser.parseKotlinFile(deepNestedKt)
        val fqIds = deepParsed.classes.map { it.fqId }.toSet()
        assertTrue("com.acme.events.DomainEvent" in fqIds)
        assertTrue("com.acme.events.DomainEvent.OrderEvent" in fqIds)
        assertTrue("com.acme.events.DomainEvent.OrderEvent.OrderCreated" in fqIds)

        // Verify TypeScript multiline member_expression, `awaitingClient` identifier, and AST argument identifier matching
        val multilineTs = """
            export interface OrderResp { id: string; }
            export interface CreateOrderBody { sku: string; }
            const awaitingClient = axios;
            export async function createWithMultiline(id: string, validBody: CreateOrderBody): Promise<OrderResp> {
                const r = await awaitingClient
                  .post<OrderResp>(`/api/v1/orders/${'$'}{id}`, validBody);
                return r.data;
            }
        """.trimIndent()
        val mlTsAst = TypeScriptAstParser.parseTypeScriptFile(multilineTs)
        assertEquals(1, mlTsAst.contracts.size)
        val mlContract = mlTsAst.contracts.single()
        assertEquals("POST", mlContract.methodOrAction)
        assertEquals("CreateOrderBody", mlContract.requestDto, "`id` used in URL must not be confused with `validBody` containing substring 'id'")
        assertEquals("OrderResp", mlContract.responseDto)

        // Verify Python multiline call, aliased import with tabs/multiple spaces, and typed FastAPI path parameter `{order_id:int}`
        val pyEdgeCases = """
            import   app.models   as   m
            from fastapi import APIRouter
            from pydantic import BaseModel

            class OrderBody(BaseModel):
                sku: str

            router = APIRouter(prefix="/v1")

            @router.post("/orders/{order_id:int}")
            def update_order(order_id: int, payload: OrderBody) -> OrderBody:
                return payload
        """.trimIndent()
        val pyEdgeAst = PythonAstParser.parsePythonFile(pyEdgeCases)
        assertEquals(listOf("app.models", "fastapi", "pydantic"), pyEdgeAst.importModules)
        assertEquals(1, pyEdgeAst.contracts.size)
        assertEquals("OrderBody", pyEdgeAst.contracts.single().requestDto)

        // Verify @RequestMapping("/api/OUTPUT_FILES") without explicit method defaults to GET (not PUT)
        val outputRouteContracts = AstTypeUnwrapper.buildContractsForClass(
            annotations = emptyList(),
            methods = listOf(
                AstMethod(
                    name = "getOutputFiles",
                    parameters = emptyList(),
                    returnType = AstTypeRef("OrderDto"),
                    annotations = listOf(
                        AstAnnotation(
                            name = "RequestMapping",
                            positionalStrings = listOf("/api/OUTPUT_FILES"),
                            rawTokensText = "@RequestMapping(\"/api/OUTPUT_FILES\")"
                        )
                    )
                )
            ),
            inlineRoutes = emptyList(),
            dtoDefinitions = emptyMap(),
            openApiOperations = emptyMap(),
            isControllerOrApiImpl = true
        )
        assertEquals("GET", outputRouteContracts.single().methodOrAction, "Path containing 'PUT' substring must not be misclassified as PUT verb")
    }
}
