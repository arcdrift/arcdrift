import { describe, expect, it } from 'vitest';
import {
  computeCanvasMetrics,
  DEMO_FALLBACK_GRAPH,
  diffStatusBadgeColor,
  filterGraphByZoomAndDiff,
  pluralize,
  sendIdeMessage
} from '../bridge/ideBridge';
import { computeElkLayout } from '../layout/elkLayout';
import {
  extractToolCallFileReferences,
  getFileTypeVisual,
  parseFileReference
} from '../components/AgentMarkdownRenderer';

describe('ArcDrift Webview Canvas Model & Layout', () => {
  it('filters nodes and edges by C4 level, diff-only mode, and search query', () => {
    // Only diff and blast nodes
    const diffOnly = filterGraphByZoomAndDiff(
      DEMO_FALLBACK_GRAPH,
      'L3_COMPONENT',
      true,
      ''
    );
    // OrderService is UNCHANGED so it should be excluded in diffOnly mode
    expect(diffOnly.nodes.some((n) => n.label === 'OrderService')).toBe(false);
    expect(diffOnly.nodes.some((n) => n.label === 'OrderController')).toBe(true);
    expect(diffOnly.nodes.some((n) => n.label === 'PaymentGatewayPort')).toBe(true);

    // Search query filter
    const searched = filterGraphByZoomAndDiff(
      DEMO_FALLBACK_GRAPH,
      'L4_CODE',
      false,
      '@RestController'
    );
    expect(searched.nodes).toHaveLength(1);
    expect(searched.nodes[0].label).toBe('OrderController');

    // L2_CONTAINER rollup with package containers, child components, and multiple edges between same containers
    const l2Graph = filterGraphByZoomAndDiff(
      {
        ...DEMO_FALLBACK_GRAPH,
        nodes: [
          {
            id: 'com.acme.empty',
            label: 'empty',
            c4Level: 'L2_CONTAINER',
            kind: 'PACKAGE',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'UNCHANGED'
          },
          {
            id: 'com.acme.orders.controller',
            label: 'controller',
            c4Level: 'L2_CONTAINER',
            kind: 'PACKAGE',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'UNCHANGED'
          },
          {
            id: 'com.acme.orders.repository',
            label: 'repository',
            c4Level: 'L2_CONTAINER',
            kind: 'PACKAGE',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'UNCHANGED'
          },
          ...DEMO_FALLBACK_GRAPH.nodes.map((n) =>
            n.id.includes('.controller.')
              ? { ...n, parentId: 'com.acme.orders.controller' }
              : n.id.includes('.repository.')
                ? { ...n, parentId: 'com.acme.orders.repository' }
                : n
          )
        ],
        edges: [
          ...DEMO_FALLBACK_GRAPH.edges,
          {
            id: 'e-extra-ctrl-repo',
            sourceId: 'com.acme.orders.controller.OrderController',
            targetId: 'com.acme.orders.repository.OrderRepository',
            kind: 'CALLS',
            label: 'findAll()',
            weight: 1,
            inCycle: false,
            diffStatus: 'UNCHANGED'
          }
        ]
      },
      'L2_CONTAINER',
      false,
      ''
    );
    expect(l2Graph.nodes.some((n) => n.id === 'com.acme.empty')).toBe(false);
    const ctrlPkg = l2Graph.nodes.find((n) => n.id === 'com.acme.orders.controller');
    const repoPkg = l2Graph.nodes.find((n) => n.id === 'com.acme.orders.repository');
    expect(repoPkg?.diffStatus).toBe('VIOLATION');
    expect(ctrlPkg?.contracts.length).toBeGreaterThan(0);
    expect(l2Graph.edges.some((e) => e.id === 'l2-com.acme.orders.controller-com.acme.orders.repository')).toBe(true);

    // L2_CONTAINER + onlyDiffAndBlast=true retains UNCHANGED package containers that contain VIOLATION/MODIFIED/GHOST_SPEC descendants,
    // rolls up multi-level nested parentIds, tolerates cyclic parentIds without hanging, and clears orphan parentIds
    const nestedAndDiffL2 = filterGraphByZoomAndDiff(
      {
        projectName: 'nested-l2-test',
        metadata: {},
        rules: [],
        nodes: [
          {
            id: 'com.acme.clean',
            label: 'clean',
            c4Level: 'L2_CONTAINER',
            kind: 'PACKAGE',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'UNCHANGED'
          },
          {
            id: 'com.acme.clean.CleanService',
            label: 'CleanService',
            c4Level: 'L3_COMPONENT',
            kind: 'SERVICE_BEAN',
            parentId: 'com.acme.clean',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'UNCHANGED'
          },
          {
            id: 'com.acme.orders',
            label: 'orders',
            c4Level: 'L2_CONTAINER',
            kind: 'PACKAGE',
            parentId: 'nonexistent.root.pkg',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'UNCHANGED'
          },
          {
            id: 'com.acme.orders.Outer',
            label: 'Outer',
            c4Level: 'L3_COMPONENT',
            kind: 'CLASS',
            parentId: 'com.acme.orders',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'UNCHANGED'
          },
          {
            id: 'com.acme.orders.Outer.InnerController',
            label: 'InnerController',
            c4Level: 'L3_COMPONENT',
            kind: 'REST_CONTROLLER',
            parentId: 'com.acme.orders.Outer',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'MODIFIED'
          },
          {
            id: 'com.acme.repo',
            label: 'repo',
            c4Level: 'L2_CONTAINER',
            kind: 'PACKAGE',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'UNCHANGED'
          },
          {
            id: 'com.acme.repo.OrderRepo',
            label: 'OrderRepo',
            c4Level: 'L3_COMPONENT',
            kind: 'REPOSITORY',
            parentId: 'com.acme.repo',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'VIOLATION'
          },
          {
            id: 'cycle.pkg.A',
            label: 'A',
            c4Level: 'L2_CONTAINER',
            kind: 'PACKAGE',
            parentId: 'cycle.pkg.B',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'MODIFIED'
          },
          {
            id: 'cycle.pkg.B',
            label: 'B',
            c4Level: 'L2_CONTAINER',
            kind: 'PACKAGE',
            parentId: 'cycle.pkg.A',
            stereotypes: [],
            members: [],
            contracts: [],
            diffStatus: 'MODIFIED'
          }
        ],
        edges: [
          {
            id: 'e-nested-cross',
            sourceId: 'com.acme.orders.Outer.InnerController',
            targetId: 'com.acme.repo.OrderRepo',
            kind: 'DEPENDS_ON',
            label: 'uses',
            weight: 1,
            inCycle: false,
            diffStatus: 'VIOLATION'
          }
        ]
      },
      'L2_CONTAINER',
      true,
      ''
    );
    expect(nestedAndDiffL2.nodes.some((n) => n.id === 'com.acme.clean')).toBe(false);
    const retainedOrders = nestedAndDiffL2.nodes.find((n) => n.id === 'com.acme.orders');
    const retainedRepo = nestedAndDiffL2.nodes.find((n) => n.id === 'com.acme.repo');
    expect(retainedOrders).toBeDefined();
    expect(retainedOrders?.parentId).toBeUndefined();
    expect(retainedRepo?.diffStatus).toBe('VIOLATION');
    expect(nestedAndDiffL2.edges.some((e) => e.id === 'l2-com.acme.orders-com.acme.repo')).toBe(true);
  });

  it('computes accurate health metrics and diff status badges', () => {
    const metrics = computeCanvasMetrics(DEMO_FALLBACK_GRAPH);
    expect(metrics.totalNodes).toBe(5);
    expect(metrics.addedCount).toBe(1);
    expect(metrics.modifiedCount).toBe(1);
    expect(metrics.blastImpactedCount).toBe(1);
    expect(metrics.violationCount).toBeGreaterThanOrEqual(2);
    expect(metrics.contractCount).toBe(2);

    expect(diffStatusBadgeColor('VIOLATION').badgeText).toBe('! VIOLATION');
    expect(diffStatusBadgeColor('ADDED').badgeText).toBe('+ ADDED');
    expect(diffStatusBadgeColor('MODIFIED').badgeText).toBe('~ MODIFIED');
    expect(diffStatusBadgeColor('REMOVED').badgeText).toBe('- REMOVED');
    expect(diffStatusBadgeColor('BLAST_IMPACTED').badgeText).toBe('⚡ BLAST RADIUS');
    expect(diffStatusBadgeColor('UNCHANGED').badgeText).toBe('CLEAN');

    // Verify sendIdeMessage queues when __arcdriftPostMessage is absent and dispatches when present
    const g = globalThis as unknown as {
      window?: {
        __arcdriftPostMessage?: (s: string) => void;
        __arcdriftPendingOutbound?: string[];
      };
    };
    const prevWin = g.window;
    const sent: string[] = [];
    g.window = {};
    sendIdeMessage({ type: 'REQUEST_INITIAL_GRAPH' });
    expect(g.window.__arcdriftPendingOutbound).toHaveLength(1);
    g.window.__arcdriftPostMessage = (payload) => sent.push(payload);
    sendIdeMessage({ type: 'CANCEL_AGENT_RUN' });
    expect(sent).toHaveLength(1);
    g.window = prevWin;

    expect(pluralize(0, 'Violation')).toBe('0 Violations');
    expect(pluralize(1, 'Violation')).toBe('1 Violation');
    expect(pluralize(2, 'Violation')).toBe('2 Violations');
    expect(pluralize(1, 'Contract')).toBe('1 Contract');
    expect(pluralize(2, 'Contract')).toBe('2 Contracts');
  });

  it('computes deterministic Dagre layered layout coordinates including empty graphs and pinned positions', async () => {
    const emptyLayout = await computeElkLayout({ ...DEMO_FALLBACK_GRAPH, nodes: [], edges: [] }, false);
    expect(emptyLayout.positions).toEqual({});

    const layout = await computeElkLayout(DEMO_FALLBACK_GRAPH, false);
    expect(Object.keys(layout.positions)).toHaveLength(DEMO_FALLBACK_GRAPH.nodes.length);
    for (const n of DEMO_FALLBACK_GRAPH.nodes) {
      expect(typeof layout.positions[n.id].x).toBe('number');
      expect(typeof layout.positions[n.id].y).toBe('number');
    }

    const pinnedNode = {
      ...DEMO_FALLBACK_GRAPH.nodes[0],
      layoutX: 420,
      layoutY: 180,
      contracts: [
        ...DEMO_FALLBACK_GRAPH.nodes[0].contracts,
        ...DEMO_FALLBACK_GRAPH.nodes[0].contracts,
        ...DEMO_FALLBACK_GRAPH.nodes[0].contracts
      ]
    };
    const pinnedLayout = await computeElkLayout(
      { ...DEMO_FALLBACK_GRAPH, nodes: [pinnedNode, ...DEMO_FALLBACK_GRAPH.nodes.slice(1)] },
      true
    );
    expect(pinnedLayout.positions[pinnedNode.id]).toEqual({ x: 420, y: 180 });
  });

  it('parses file references, line numbers, graph nodes, and file-type visuals for clickable chips', () => {
    const ktRef = parseFileReference(
      'src/main/kotlin/com/acme/orders/controller/OrderController.kt:14',
      DEMO_FALLBACK_GRAPH.nodes
    );
    expect(ktRef).not.toBeNull();
    expect(ktRef?.displayName).toBe('OrderController.kt');
    expect(ktRef?.line).toBe(14);
    expect(ktRef?.lineLabel).toBe(':14');
    expect(ktRef?.visual.category).toBe('kotlin');

    const uriRef = parseFileReference(
      'file:///workspace/web/ordersClient.ts#L10-L22',
      DEMO_FALLBACK_GRAPH.nodes
    );
    expect(uriRef).not.toBeNull();
    expect(uriRef?.displayName).toBe('ordersClient.ts');
    expect(uriRef?.line).toBe(10);
    expect(uriRef?.endLine).toBe(22);
    expect(uriRef?.lineLabel).toBe('#L10-22');
    expect(uriRef?.visual.category).toBe('typescript');

    // Resolve bare ArcNode component name to a clickable file chip
    const nodeRef = parseFileReference('OrderController', DEMO_FALLBACK_GRAPH.nodes);
    expect(nodeRef).not.toBeNull();
    expect(nodeRef?.displayName).toBe('OrderController');
    expect(nodeRef?.nodeId).toBe('com.acme.orders.controller.OrderController');
    expect(nodeRef?.visual.category).toBe('kotlin');

    // Resolve generic `.arc.mmd` reference to `architecture.arc.mmd`
    const arcSpecRef = parseFileReference('.arc.mmd', DEMO_FALLBACK_GRAPH.nodes);
    expect(arcSpecRef).not.toBeNull();
    expect(arcSpecRef?.filePath).toBe('architecture.arc.mmd');
    expect(arcSpecRef?.displayName).toBe('architecture.arc.mmd');
    expect(arcSpecRef?.visual.category).toBe('mermaid');

    // Non-file identifier should not be turned into a file chip
    expect(parseFileReference('verify_architectural_compliance', DEMO_FALLBACK_GRAPH.nodes)).toBeNull();
    expect(parseFileReference('https://example.com/docs', DEMO_FALLBACK_GRAPH.nodes)).toBeNull();

    // Tool call file extraction
    const toolRefs = extractToolCallFileReferences(
      'write_project_file (src/main/kotlin/com/acme/controller/OrderController.kt)',
      JSON.stringify({ filePath: 'src/main/kotlin/com/acme/controller/OrderController.kt' }),
      DEMO_FALLBACK_GRAPH.nodes
    );
    expect(toolRefs).toHaveLength(1);
    expect(toolRefs[0].displayName).toBe('OrderController.kt');

    expect(getFileTypeVisual('OrderService.java').category).toBe('java');
    expect(getFileTypeVisual('consumer.py').category).toBe('python');
    expect(getFileTypeVisual('architecture.arc.mmd').category).toBe('mermaid');
  });
});
