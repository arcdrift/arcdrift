import dagre from '@dagrejs/dagre';
import { ArcGraph, ArcNode } from '../types/bridge';

export interface PositionedArcGraph {
  positions: Record<string, { x: number; y: number }>;
}

const NODE_CARD_WIDTH = 300;

function estimateNodeCardHeight(n: ArcNode): number {
  let h = 132;
  if (n.contracts && n.contracts.length > 0) {
    const visibleContracts = Math.min(2, n.contracts.length);
    h += visibleContracts * 34 + (n.contracts.length > 2 ? 22 : 6);
  }
  if (n.members && n.members.length > 0) h += 34;
  const vCount = n.violations?.length ?? (n.diffStatus === 'VIOLATION' ? 1 : 0);
  if (vCount > 0) {
    h += 28 + vCount * 52;
  }
  return h;
}

/**
 * Computes layered hierarchical coordinates for an ArcGraph using @dagrejs/dagre (MIT license).
 */
export async function computeLayeredLayout(
  graph: ArcGraph,
  respectManualPositions: boolean = false
): Promise<PositionedArcGraph> {
  if (graph.nodes.length === 0) {
    return { positions: {} };
  }

  try {
    const g = new dagre.graphlib.Graph({ multigraph: false, compound: false });
    g.setGraph({
      rankdir: 'TB',
      nodesep: 100,
      edgesep: 60,
      ranksep: 140,
      marginx: 60,
      marginy: 60
    });
    g.setDefaultEdgeLabel(() => ({}));

    const nodeIds = new Set(graph.nodes.map((n) => n.id));
    const nodeHeights: Record<string, number> = {};

    for (const n of graph.nodes) {
      const h = estimateNodeCardHeight(n);
      nodeHeights[n.id] = h;
      g.setNode(n.id, { width: NODE_CARD_WIDTH, height: h });
    }

    for (const e of graph.edges) {
      if (nodeIds.has(e.sourceId) && nodeIds.has(e.targetId) && e.sourceId !== e.targetId) {
        g.setEdge(e.sourceId, e.targetId);
      }
    }

    dagre.layout(g);

    const positions: Record<string, { x: number; y: number }> = {};
    for (const n of graph.nodes) {
      if (
        respectManualPositions &&
        typeof n.layoutX === 'number' &&
        typeof n.layoutY === 'number'
      ) {
        positions[n.id] = { x: n.layoutX, y: n.layoutY };
        continue;
      }
      const dagreNode = g.node(n.id);
      const h = nodeHeights[n.id] ?? 132;
      const rawX = dagreNode ? dagreNode.x - NODE_CARD_WIDTH / 2 : 80;
      const rawY = dagreNode ? dagreNode.y - h / 2 : 80;
      positions[n.id] = {
        x: Math.round(rawX / 20) * 20,
        y: Math.round(rawY / 20) * 20
      };
    }

    return { positions };
  } catch {
    const fallback: Record<string, { x: number; y: number }> = {};
    graph.nodes.forEach((n, idx) => {
      fallback[n.id] = {
        x: Math.round(((idx % 3) * 380 + 60) / 20) * 20,
        y: Math.round((Math.floor(idx / 3) * 300 + 60) / 20) * 20
      };
    });
    return { positions: fallback };
  }
}

export const computeElkLayout = computeLayeredLayout;
