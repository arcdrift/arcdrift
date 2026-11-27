import {
  ArcGraph,
  C4Level,
  DiffStatus,
  IncomingIdeMessage,
  OutgoingIdeMessage
} from '../types/bridge';

declare global {
  interface Window {
    __arcdriftPostMessage?: (payloadJson: string) => void;
    __arcdriftReceiveMessage?: (message: IncomingIdeMessage) => void;
    __arcdriftPendingInbound?: IncomingIdeMessage[];
    __arcdriftPendingOutbound?: string[];
  }
}

/**
 * Pure helper functions for filtering and summarizing an ArcGraph on the canvas.
 * Exported so Vitest unit tests can verify UI/UX model logic deterministically.
 */
export function pluralize(count: number, singular: string, plural?: string): string {
  const word = count === 1 ? singular : (plural ?? `${singular}s`);
  return `${count} ${word}`;
}

export function filterGraphByZoomAndDiff(
  graph: ArcGraph,
  maxZoomLevel: C4Level,
  onlyDiffAndBlast: boolean,
  searchQuery: string = ''
): ArcGraph {
  const levelOrder: Record<C4Level, number> = {
    L1_SYSTEM: 0,
    L2_CONTAINER: 1,
    L3_COMPONENT: 2,
    L4_CODE: 3
  };
  const maxOrdinal = levelOrder[maxZoomLevel];
  const query = searchQuery.trim().toLowerCase();

  const edgeImpactedNodeIds = new Set<string>();
  const allEdgeEndpointIds = new Set<string>();
  for (const e of graph.edges) {
    allEdgeEndpointIds.add(e.sourceId);
    allEdgeEndpointIds.add(e.targetId);
    if (e.diffStatus === 'VIOLATION' || e.inCycle) {
      edgeImpactedNodeIds.add(e.sourceId);
      edgeImpactedNodeIds.add(e.targetId);
    }
  }

  const nodeById = new Map(graph.nodes.map((n) => [n.id, n]));
  const resolveEffectiveContainerId = (startId: string): string => {
    const visited = new Set<string>([startId]);
    let curr = nodeById.get(startId);
    if (curr && levelOrder[curr.c4Level] <= maxOrdinal) {
      return curr.id;
    }
    let candidate = startId;
    while (curr?.parentId && !visited.has(curr.parentId)) {
      visited.add(curr.parentId);
      const parent = nodeById.get(curr.parentId);
      if (!parent) break;
      if (levelOrder[parent.c4Level] <= maxOrdinal) {
        candidate = parent.id;
        break;
      }
      curr = parent;
    }
    return candidate;
  };

  const containerIdsWithImpactedDescendants = new Set<string>();
  if (onlyDiffAndBlast) {
    for (const n of graph.nodes) {
      const isNodeImpacted =
        n.diffStatus !== 'UNCHANGED' ||
        n.kind === 'GHOST_SPEC' ||
        edgeImpactedNodeIds.has(n.id);
      if (!isNodeImpacted) continue;
      const visited = new Set<string>([n.id]);
      let pId = n.parentId;
      while (pId && !visited.has(pId)) {
        visited.add(pId);
        containerIdsWithImpactedDescendants.add(pId);
        pId = nodeById.get(pId)?.parentId;
      }
    }
  }

  const hasComponentNodes = graph.nodes.some(
    (n) => n.c4Level === 'L3_COMPONENT' || n.c4Level === 'L4_CODE'
  );
  const hasPackageContainers = graph.nodes.some((n) => n.kind === 'PACKAGE');

  const visibleNodes = graph.nodes.filter((n) => {
    const isRootPolyglotClientInL2 =
      maxZoomLevel === 'L2_CONTAINER' &&
      hasPackageContainers &&
      !n.parentId &&
      (n.kind === 'TS_CLIENT' || n.kind === 'PYTHON_CONSUMER');
    if (levelOrder[n.c4Level] > maxOrdinal && !isRootPolyglotClientInL2) return false;
    // Hide unconnected L2 package container nodes when viewing L3/L4 component diagrams
    if (
      maxOrdinal >= levelOrder.L3_COMPONENT &&
      hasComponentNodes &&
      n.kind === 'PACKAGE' &&
      !allEdgeEndpointIds.has(n.id)
    ) {
      return false;
    }
    if (
      onlyDiffAndBlast &&
      n.diffStatus === 'UNCHANGED' &&
      n.kind !== 'GHOST_SPEC' &&
      !edgeImpactedNodeIds.has(n.id) &&
      !containerIdsWithImpactedDescendants.has(n.id)
    ) {
      return false;
    }
    if (query.length > 0) {
      const matchId = n.id.toLowerCase().includes(query);
      const matchLabel = n.label.toLowerCase().includes(query);
      const matchStereo = n.stereotypes.some((s) => s.toLowerCase().includes(query));
      return matchId || matchLabel || matchStereo;
    }
    return true;
  });

  const visibleIds = new Set(visibleNodes.map((n) => n.id));
  const visibleEdges = graph.edges.filter(
    (e) => visibleIds.has(e.sourceId) && visibleIds.has(e.targetId)
  );

  // If viewing L2_CONTAINER and edges exist between child components, roll up edges, status, contracts, and members to parent containers
  if (maxZoomLevel === 'L2_CONTAINER' && graph.edges.length > 0) {
    const containerEdgesMap = new Map<string, typeof graph.edges[number]>();
    for (const e of graph.edges) {
      const srcParent = resolveEffectiveContainerId(e.sourceId);
      const tgtParent = resolveEffectiveContainerId(e.targetId);
      if (srcParent !== tgtParent && visibleIds.has(srcParent) && visibleIds.has(tgtParent)) {
        const key = `${srcParent}->${tgtParent}`;
        const existing = containerEdgesMap.get(key);
        if (!existing) {
          containerEdgesMap.set(key, {
            ...e,
            id: `l2-${srcParent}-${tgtParent}`,
            sourceId: srcParent,
            targetId: tgtParent
          });
        } else {
          const mergedViolations = Array.from(
            new Set([...(existing.violations ?? []), ...(e.violations ?? [])])
          );
          const isViolation = existing.diffStatus === 'VIOLATION' || e.diffStatus === 'VIOLATION';
          containerEdgesMap.set(key, {
            ...(e.diffStatus === 'VIOLATION' ? e : existing),
            id: `l2-${srcParent}-${tgtParent}`,
            sourceId: srcParent,
            targetId: tgtParent,
            inCycle: existing.inCycle || e.inCycle,
            diffStatus: isViolation ? 'VIOLATION' : existing.diffStatus,
            violations: mergedViolations
          });
        }
      }
    }

    const rolledUpEdges = Array.from(containerEdgesMap.values());
    const connectedContainerIds = new Set<string>();
    for (const e of rolledUpEdges) {
      connectedContainerIds.add(e.sourceId);
      connectedContainerIds.add(e.targetId);
    }

    const childrenByParent = new Map<string, typeof graph.nodes>();
    for (const n of graph.nodes) {
      if (n.parentId) {
        const list = childrenByParent.get(n.parentId) ?? [];
        list.push(n);
        childrenByParent.set(n.parentId, list);
      }
    }

    const enrichedContainers = visibleNodes
      .filter((n) => {
        const children = childrenByParent.get(n.id) ?? [];
        // Hide empty ancestor packages (like `com.acme`) that have no direct classes and no edges
        if (n.kind === 'PACKAGE' && children.length === 0 && !connectedContainerIds.has(n.id)) {
          return false;
        }
        return true;
      })
      .map((n) => {
        const children = childrenByParent.get(n.id) ?? [];
        if (children.length === 0) return n;
        const childViolations = Array.from(
          new Set([
            ...(n.violations ?? []),
            ...children.flatMap((c) => c.violations ?? [])
          ])
        );
        const hasChildViolation =
          n.diffStatus === 'VIOLATION' ||
          childViolations.length > 0 ||
          children.some((c) => c.diffStatus === 'VIOLATION');
        const childMembers =
          n.members.length > 0
            ? n.members
            : children.map((c) => ({
                name: c.label,
                kind: 'COMPONENT',
                typeOrSignature: ` (${c.kind.replace(/_/g, ' ').toLowerCase()})`,
                visibility: 'public'
              }));
        const childContracts =
          n.contracts.length > 0
            ? n.contracts
            : children.flatMap((c) => c.contracts ?? []);
        return {
          ...n,
          parentId: n.parentId && n.parentId !== n.id ? n.parentId : undefined,
          diffStatus: hasChildViolation ? ('VIOLATION' as const) : n.diffStatus,
          violations: childViolations,
          members: childMembers,
          contracts: childContracts,
          source: n.source ?? children.find((c) => c.source)?.source
        };
      });

    const finalContainerIds = new Set(enrichedContainers.map((n) => n.id));
    const sanitizedContainers = enrichedContainers.map((n) =>
      n.parentId && !finalContainerIds.has(n.parentId) ? { ...n, parentId: undefined } : n
    );

    return {
      ...graph,
      nodes: sanitizedContainers,
      edges: rolledUpEdges
    };
  }

  const sanitizedVisibleNodes = visibleNodes.map((n) =>
    n.parentId && (!visibleIds.has(n.parentId) || n.parentId === n.id)
      ? { ...n, parentId: undefined }
      : n
  );

  return {
    ...graph,
    nodes: sanitizedVisibleNodes,
    edges: visibleEdges
  };
}

export interface CanvasHealthMetrics {
  totalNodes: number;
  addedCount: number;
  modifiedCount: number;
  violationCount: number;
  blastImpactedCount: number;
  cycleEdgeCount: number;
  contractCount: number;
}

export function computeCanvasMetrics(graph: ArcGraph): CanvasHealthMetrics {
  let addedCount = 0;
  let modifiedCount = 0;
  let blastImpactedCount = 0;
  let contractCount = 0;

  const hasDetailedViolations =
    graph.nodes.some((n) => (n.violations?.length ?? 0) > 0) ||
    graph.edges.some((e) => (e.violations?.length ?? 0) > 0);

  const nodesCoveredByViolatedEdges = new Set<string>();
  let edgeViolationCount = 0;

  for (const e of graph.edges) {
    if (e.diffStatus === 'VIOLATION') {
      nodesCoveredByViolatedEdges.add(e.sourceId);
      nodesCoveredByViolatedEdges.add(e.targetId);
      edgeViolationCount += Math.max(1, e.violations?.length ?? 1);
    }
  }

  let nodeViolationCount = 0;
  for (const n of graph.nodes) {
    if (n.diffStatus === 'ADDED') addedCount++;
    if (n.diffStatus === 'MODIFIED') modifiedCount++;
    if (n.diffStatus === 'VIOLATION') {
      if (!hasDetailedViolations) {
        nodeViolationCount++;
      } else if (!nodesCoveredByViolatedEdges.has(n.id)) {
        nodeViolationCount += Math.max(1, n.violations?.length ?? 1);
      }
    }
    if (n.diffStatus === 'BLAST_IMPACTED') blastImpactedCount++;
    contractCount += n.contracts.length;
  }

  const violationCount = nodeViolationCount + edgeViolationCount;
  const cycleEdgeCount = graph.edges.filter((e) => e.inCycle).length;

  return {
    totalNodes: graph.nodes.length,
    addedCount,
    modifiedCount,
    violationCount,
    blastImpactedCount,
    cycleEdgeCount,
    contractCount
  };
}

export function diffStatusBadgeColor(status: DiffStatus): {
  border: string;
  bg: string;
  badgeText: string;
  badgeColor: string;
} {
  switch (status) {
    case 'ADDED':
      return {
        border: '#52525b',
        bg: '#18181b',
        badgeText: '+ ADDED',
        badgeColor: '#a1a1aa'
      };
    case 'MODIFIED':
      return {
        border: '#94949e',
        bg: '#18181b',
        badgeText: '~ MODIFIED',
        badgeColor: '#d4d4d8'
      };
    case 'REMOVED':
      return {
        border: '#3f3f46',
        bg: '#18181b',
        badgeText: '- REMOVED',
        badgeColor: '#94949e'
      };
    case 'VIOLATION':
      return {
        border: '#ef4444',
        bg: '#1c1315',
        badgeText: '! VIOLATION',
        badgeColor: '#f87171'
      };
    case 'BLAST_IMPACTED':
      return {
        border: '#52525b',
        bg: '#18181b',
        badgeText: '⚡ BLAST RADIUS',
        badgeColor: '#cbd5e1'
      };
    default:
      return {
        border: '#27272a',
        bg: '#18181b',
        badgeText: 'CLEAN',
        badgeColor: '#94949e'
      };
  }
}

export function sendIdeMessage(msg: OutgoingIdeMessage): void {
  const serialized = JSON.stringify(msg);
  if (typeof window !== 'undefined') {
    if (window.__arcdriftPostMessage) {
      window.__arcdriftPostMessage(serialized);
    } else {
      window.__arcdriftPendingOutbound = window.__arcdriftPendingOutbound || [];
      window.__arcdriftPendingOutbound.push(serialized);
    }
  }
}

export const DEMO_FALLBACK_GRAPH: ArcGraph = {
  projectName: 'commerce-platform',
  metadata: { version: '2.1' },
  rules: [
    {
      id: 'no-controller-to-repo',
      name: 'Controllers Must Not Call Repositories Directly',
      fromPattern: '*.controller.*',
      toPattern: '*.repository.*',
      policy: 'FORBIDDEN',
      rationale: 'Route all persistence mutations through transactional Domain Services.'
    }
  ],
  nodes: [
    {
      id: 'web.checkout.ordersClient',
      label: 'ordersClient.ts',
      c4Level: 'L3_COMPONENT',
      kind: 'TS_CLIENT',
      stereotypes: ['TypeScript Fetch Client'],
      members: [{ name: 'fetchOrder', kind: 'METHOD', typeOrSignature: '(id: string): Promise<OrderDto>', visibility: 'public' }],
      contracts: [
        {
          protocol: 'REST',
          methodOrAction: 'GET',
          pathOrTopic: '/v1/orders/{id}',
          role: 'CONSUMER',
          responseDto: 'OrderDto'
        }
      ],
      diffStatus: 'BLAST_IMPACTED'
    },
    {
      id: 'com.acme.orders.controller.OrderController',
      label: 'OrderController',
      c4Level: 'L3_COMPONENT',
      kind: 'REST_CONTROLLER',
      stereotypes: ['@RestController', '@RequestMapping("/v1/orders")'],
      members: [{ name: 'getOrder', kind: 'ENDPOINT', typeOrSignature: 'GET /v1/orders/{id} -> OrderDto', visibility: 'public' }],
      contracts: [
        {
          protocol: 'REST',
          methodOrAction: 'GET',
          pathOrTopic: '/v1/orders/{id}',
          role: 'PRODUCER',
          responseDto: 'OrderDto'
        }
      ],
      diffStatus: 'MODIFIED'
    },
    {
      id: 'com.acme.orders.service.OrderService',
      label: 'OrderService',
      c4Level: 'L3_COMPONENT',
      kind: 'SERVICE_BEAN',
      stereotypes: ['@Service', '@Transactional'],
      members: [{ name: 'loadOrder', kind: 'METHOD', typeOrSignature: '(id: String): OrderDto', visibility: 'public' }],
      contracts: [],
      diffStatus: 'UNCHANGED'
    },
    {
      id: 'com.acme.orders.repository.OrderRepository',
      label: 'OrderRepository',
      c4Level: 'L3_COMPONENT',
      kind: 'REPOSITORY',
      stereotypes: ['@Repository', 'JpaRepository'],
      members: [{ name: 'findById', kind: 'METHOD', typeOrSignature: '(id: String): OrderEntity?', visibility: 'public' }],
      contracts: [],
      diffStatus: 'VIOLATION'
    },
    {
      id: 'com.acme.orders.port.PaymentGatewayPort',
      label: 'PaymentGatewayPort',
      c4Level: 'L3_COMPONENT',
      kind: 'GHOST_SPEC',
      stereotypes: ['«Ghost Spec Interface»'],
      members: [{ name: 'authorizeCharge', kind: 'METHOD', typeOrSignature: '(amountCents: Long): ChargeToken', visibility: 'public' }],
      contracts: [],
      diffStatus: 'ADDED'
    }
  ],
  edges: [
    {
      id: 'e-http-ts-ctrl',
      sourceId: 'web.checkout.ordersClient',
      targetId: 'com.acme.orders.controller.OrderController',
      kind: 'HTTP_CONTRACT',
      label: 'GET /v1/orders/{id}',
      weight: 1,
      inCycle: false,
      diffStatus: 'BLAST_IMPACTED'
    },
    {
      id: 'e-ctrl-svc',
      sourceId: 'com.acme.orders.controller.OrderController',
      targetId: 'com.acme.orders.service.OrderService',
      kind: 'CALLS',
      label: 'loadOrder()',
      weight: 2,
      inCycle: false,
      diffStatus: 'UNCHANGED'
    },
    {
      id: 'e-svc-repo',
      sourceId: 'com.acme.orders.service.OrderService',
      targetId: 'com.acme.orders.repository.OrderRepository',
      kind: 'DEPENDS_ON',
      label: 'findById()',
      weight: 1,
      inCycle: false,
      diffStatus: 'UNCHANGED'
    },
    {
      id: 'e-violation-ctrl-repo',
      sourceId: 'com.acme.orders.controller.OrderController',
      targetId: 'com.acme.orders.repository.OrderRepository',
      kind: 'FORBIDDEN_RULE',
      label: 'VIOLATION: no-controller-to-repo',
      weight: 1,
      inCycle: false,
      diffStatus: 'VIOLATION'
    },
    {
      id: 'e-svc-ghost',
      sourceId: 'com.acme.orders.service.OrderService',
      targetId: 'com.acme.orders.port.PaymentGatewayPort',
      kind: 'DEPENDS_ON',
      label: 'spec: authorizeCharge()',
      weight: 1,
      inCycle: false,
      diffStatus: 'ADDED'
    }
  ]
};
