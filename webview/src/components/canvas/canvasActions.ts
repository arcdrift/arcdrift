import { Edge, Node } from '@xyflow/react';
import { pluralize } from '../../bridge/ideBridge';
import {
  AgentStepEvent,
  ArcGraph,
  ArcNode,
  BoundaryRule,
  IncomingIdeMessage,
  OutgoingIdeMessage
} from '../../types/bridge';
import { OpenFileTarget } from '../AgentMarkdownRenderer';
import { DEFAULT_AGENT_BASE_URL, sanitizeMaxTurns } from './agentConfigStorage';

export function buildReactFlowNodesAndEdges(
  filteredGraph: ArcGraph,
  positions: Record<string, { x: number; y: number }>,
  manualPositions: Record<string, { x: number; y: number }>,
  respectManualPositions: boolean,
  onNavigateToSource: (node: ArcNode) => void,
  onExtractInterface: (node: ArcNode) => void
): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = filteredGraph.nodes.map((n) => ({
    id: n.id,
    type: 'c4Card',
    position:
      (respectManualPositions ? manualPositions[n.id] : undefined) ??
      positions[n.id] ?? { x: 80, y: 80 },
    data: {
      arcNode: n,
      onNavigateToSource,
      onExtractInterface
    }
  }));

  const edges: Edge[] = filteredGraph.edges.map((e) => ({
    id: e.id,
    source: e.sourceId,
    target: e.targetId,
    type: 'constraintEdge',
    animated: e.diffStatus === 'VIOLATION' || e.inCycle,
    data: {
      arcEdge: e
    }
  }));

  return { nodes, edges };
}

export function buildNavigateToSourceAction(node: ArcNode): {
  message: OutgoingIdeMessage;
  toast: string;
} {
  return {
    message: {
      type: 'NAVIGATE_TO_SOURCE',
      nodeId: node.id,
      filePath: node.source?.filePath,
      line: node.source?.startLine
    },
    toast: `Navigated to ${node.label} (${node.source?.filePath ?? node.id})`
  };
}

export function buildOpenFileFromAgentAction(target: OpenFileTarget): {
  message: OutgoingIdeMessage;
  toast: string;
} {
  return {
    message: {
      type: 'NAVIGATE_TO_SOURCE',
      nodeId: target.nodeId ?? '',
      filePath: target.filePath,
      line: target.line ?? 1
    },
    toast: `Opening ${target.filePath}${target.line ? `:${target.line}` : ''} in IDE editor...`
  };
}

export function buildAddGhostNodeAction(
  prev: ArcGraph,
  sandboxPreviewMode: boolean
): {
  nextGraph: ArcGraph;
  ghost: ArcNode;
  message: OutgoingIdeMessage;
  toast: string;
} {
  const ghost = createNextGhostSpecNode(prev.nodes);
  return {
    nextGraph: {
      ...prev,
      nodes: [...prev.nodes, ghost]
    },
    ghost,
    message: {
      type: 'APPLY_REFACTORING',
      previewOnly: sandboxPreviewMode,
      command: {
        type: 'CREATE_NODE',
        fqName: ghost.id,
        kind: 'GHOST_SPEC',
        stereotypes: ['«Ghost Spec»']
      }
    },
    toast: `Added Ghost Spec node '${ghost.label}'`
  };
}

export function buildSaveBoundaryRuleAction(
  prev: ArcGraph,
  newRule: BoundaryRule
): {
  nextGraph: ArcGraph;
  message: OutgoingIdeMessage;
  toast: string;
} {
  return {
    nextGraph: {
      ...prev,
      rules: [...prev.rules.filter((r) => r.id !== newRule.id), newRule]
    },
    message: {
      type: 'SAVE_SPEC_RULE',
      rule: newRule
    },
    toast: `Saved guardrail rule '${newRule.id}' to architecture.arc.mmd`
  };
}

export function buildRunOpenAiAgentAction(
  graph: ArcGraph,
  agentPromptText: string,
  agentBaseUrl: string,
  agentModel: string,
  agentApiKey: string,
  agentMaxTurns: number
): {
  sanitizedTurns: number;
  initialStep: AgentStepEvent;
  message: OutgoingIdeMessage;
} {
  const sanitizedTurns = sanitizeMaxTurns(agentMaxTurns);
  const ghosts = graph.nodes.filter((n) => n.kind === 'GHOST_SPEC');
  return {
    sanitizedTurns,
    initialStep: {
      kind: 'status',
      title: `Starting Self-Healing Agent on ${agentBaseUrl}`,
      detail: `Model: ${agentModel || '(auto)'} · Max Turns: ${sanitizedTurns}`,
      turn: 0,
      maxTurns: sanitizedTurns,
      done: false,
      success: true
    },
    message: {
      type: 'HANDOFF_TO_AGENT',
      specPrompt: agentPromptText,
      ghostNodes: ghosts,
      rules: graph.rules,
      agentBaseUrl,
      agentModel,
      agentApiKey,
      maxTurns: sanitizedTurns
    }
  };
}

export interface CanvasIncomingState {
  graph: ArcGraph;
  statusToast: string;
  theme?: 'dark' | 'light';
  agentBaseUrl: string;
  agentModel: string;
  agentModels: string[];
  agentApiKey: string;
  agentHasSavedApiKey: boolean;
  agentMaxTurns: number;
  agentRunning: boolean;
  agentSteps: AgentStepEvent[];
  autoPersistModel?: string;
  saveLoadedConfig?: {
    baseUrl: string;
    model: string;
    apiKey: string;
    maxTurns: number;
  };
}

export function createNextGhostSpecNode(existingNodes: ArcNode[]): ArcNode {
  let idx = existingNodes.filter((n) => n.kind === 'GHOST_SPEC').length + 1;
  while (existingNodes.some((n) => n.id === `com.acme.spec.PlannedComponent${idx}`)) {
    idx++;
  }
  const newId = `com.acme.spec.PlannedComponent${idx}`;
  return {
    id: newId,
    label: `PlannedComponent${idx}`,
    c4Level: 'L3_COMPONENT',
    kind: 'GHOST_SPEC',
    stereotypes: ['«Ghost Spec»'],
    members: [{ name: 'execute', kind: 'METHOD', typeOrSignature: '(): Result', visibility: 'public' }],
    contracts: [],
    diffStatus: 'ADDED'
  };
}

export function reduceIncomingIdeMessage(
  prev: CanvasIncomingState,
  message: IncomingIdeMessage
): CanvasIncomingState {
  const next: CanvasIncomingState = {
    ...prev,
    autoPersistModel: undefined,
    saveLoadedConfig: undefined
  };

  if (message.type === 'GRAPH_UPDATED' || message.type === 'DIFF_UPDATED') {
    if (message.graph && Array.isArray(message.graph.nodes)) {
      next.graph = message.graph;
      next.statusToast = `Graph synchronized (${pluralize(message.graph.nodes.length, 'component')})`;
    }
  } else if (message.type === 'NOTIFICATION') {
    next.statusToast = message.message;
  } else if (message.type === 'THEME_CHANGED') {
    next.theme = message.theme;
  } else if (message.type === 'AGENT_CONFIG_LOADED') {
    const effectiveApiKey = message.apiKey || prev.agentApiKey || '';
    const loadedConfig = {
      baseUrl: message.baseUrl || DEFAULT_AGENT_BASE_URL,
      model: message.model || '',
      apiKey: effectiveApiKey,
      maxTurns: sanitizeMaxTurns(message.maxTurns)
    };
    next.agentBaseUrl = loadedConfig.baseUrl;
    next.agentModel = loadedConfig.model;
    next.agentApiKey = loadedConfig.apiKey;
    next.agentHasSavedApiKey = Boolean(message.hasSavedApiKey);
    next.agentMaxTurns = loadedConfig.maxTurns;
    next.saveLoadedConfig = loadedConfig;
  } else if (message.type === 'AGENT_MODELS_LOADED') {
    if (message.error) {
      next.agentSteps = [
        ...prev.agentSteps,
        {
          kind: 'error',
          title: 'Failed to fetch /v1/models',
          detail: message.error || 'Unknown error',
          turn: 0,
          maxTurns: prev.agentMaxTurns,
          done: false,
          success: false
        }
      ];
    } else {
      next.agentModels = message.models;
      if (message.models.length > 0 && !prev.agentModel) {
        next.agentModel = message.models[0];
        next.autoPersistModel = message.models[0];
      }
      next.agentSteps = [
        ...prev.agentSteps,
        {
          kind: 'status',
          title: `Discovered ${pluralize(message.models.length, 'model')} from ${message.baseUrl}`,
          detail: message.models.join(', '),
          turn: 0,
          maxTurns: prev.agentMaxTurns,
          done: false,
          success: true
        }
      ];
    }
  } else if (message.type === 'AGENT_STEP') {
    next.agentSteps = [...prev.agentSteps, message.step];
    next.statusToast = message.step.title;
    const autoSelectedMatch = message.step.title.match(/auto-selected '([^']+)'/);
    if (autoSelectedMatch && autoSelectedMatch[1]) {
      next.agentModel = autoSelectedMatch[1];
      next.autoPersistModel = autoSelectedMatch[1];
    }
    if (message.step.done) {
      next.agentRunning = false;
    }
  } else if (message.type === 'AGENT_PROGRESS') {
    next.agentSteps = [
      ...prev.agentSteps,
      {
        kind: message.done ? (message.success === false ? 'error' : 'complete') : 'status',
        title: message.logLine,
        detail: '',
        turn: 0,
        maxTurns: prev.agentMaxTurns,
        done: Boolean(message.done),
        success: message.success !== false
      }
    ];
    next.statusToast = message.logLine;
    if (message.done) {
      next.agentRunning = false;
    }
  }

  return next;
}

export function buildAgentHandoffPrompt(graph: ArcGraph): string {
  const ghosts = graph.nodes.filter((n) => n.kind === 'GHOST_SPEC');
  const cycleEdges = graph.edges.filter((e) => e.inCycle);
  const activeViolations = Array.from(
    new Set([
      ...graph.edges.flatMap((e) => e.violations ?? []),
      ...graph.nodes.flatMap((n) => n.violations ?? [])
    ])
  );
  const lines: string[] = [];
  if (activeViolations.length > 0) {
    lines.push('Fix the following architectural boundary/contract violations in the codebase:');
    for (const v of activeViolations) {
      lines.push(`- ${v}`);
    }
    lines.push('');
  }
  if (cycleEdges.length > 0) {
    lines.push(`Break the following ${pluralize(cycleEdges.length, 'circular dependency edge')}:`);
    for (const ce of cycleEdges) {
      lines.push(`- ${ce.sourceId} -> ${ce.targetId} (${ce.kind})`);
    }
    lines.push('');
  }
  if (ghosts.length > 0) {
    lines.push(`Implement ${pluralize(ghosts.length, 'planned Ghost Spec component')}:`);
    for (const g of ghosts) {
      lines.push(
        `- ${g.id} (${g.kind}) with members: ${g.members.map((m) => m.name + m.typeOrSignature).join(', ')}`
      );
    }
    lines.push('');
  }
  lines.push(
    `Respect ${
      graph.rules.length === 1
        ? 'the 1 architecture.arc.mmd boundary rule'
        : `all ${pluralize(graph.rules.length, 'architecture.arc.mmd boundary rule')}`
    } and call verify_architectural_compliance() to confirm 0 violations.`
  );
  return lines.join('\n');
}

export function applyExtractInterfaceToGraph(
  prev: ArcGraph,
  node: ArcNode
): { nextGraph: ArcGraph; interfaceName: string; pkg: string } {
  const pkg =
    node.parentId ||
    (node.id.includes('.')
      ? node.id.substring(0, node.id.lastIndexOf('.'))
      : 'com.acme.port');
  const interfaceName = `${node.label}Port`;
  const interfaceId = `${pkg}.${interfaceName}`;

  if (prev.nodes.some((n) => n.id === interfaceId)) {
    return { nextGraph: prev, interfaceName, pkg };
  }

  const extractedMembers = node.members.filter(
    (m) => m.kind === 'METHOD' && m.visibility === 'public'
  );

  const replaceTrailingFilename = (filePath: string): string => {
    const lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
    const dirPrefix = lastSlash >= 0 ? filePath.slice(0, lastSlash + 1) : '';
    const fileName = lastSlash >= 0 ? filePath.slice(lastSlash + 1) : filePath;
    const dotIdx = fileName.lastIndexOf('.');
    const ext = dotIdx >= 0 ? fileName.slice(dotIdx) : '.kt';
    return `${dirPrefix}${interfaceName}${ext}`;
  };

  const extractedNode: ArcNode = {
    id: interfaceId,
    label: interfaceName,
    c4Level: node.c4Level,
    kind: 'INTERFACE',
    parentId: pkg,
    stereotypes: ['«Extracted Port Interface»'],
    members: extractedMembers,
    contracts: [],
    source: node.source
      ? {
          ...node.source,
          filePath: replaceTrailingFilename(node.source.filePath)
        }
      : undefined,
    diffStatus: 'ADDED'
  };

  const rewiredEdges = prev.edges.map((e) =>
    e.targetId === node.id &&
    e.sourceId !== node.id &&
    (e.kind === 'DEPENDS_ON' || e.kind === 'CALLS')
      ? { ...e, targetId: interfaceId, diffStatus: 'MODIFIED' as const }
      : e
  );

  return {
    interfaceName,
    pkg,
    nextGraph: {
      ...prev,
      nodes: [...prev.nodes, extractedNode],
      edges: [
        ...rewiredEdges,
        {
          id: `impl-${node.label}-${interfaceName}`,
          sourceId: node.id,
          targetId: interfaceId,
          kind: 'IMPLEMENTS',
          label: 'implements',
          weight: 1,
          inCycle: false,
          diffStatus: 'ADDED'
        }
      ]
    }
  };
}
