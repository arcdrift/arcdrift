import { ReactFlowProvider, Position } from '@xyflow/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { computeCanvasMetrics, DEMO_FALLBACK_GRAPH } from '../bridge/ideBridge';
import {
  DEFAULT_AGENT_BASE_URL,
  DEFAULT_AGENT_MAX_TURNS,
  loadStoredAgentConfig,
  sanitizeMaxTurns,
  saveStoredAgentConfig
} from '../components/canvas/agentConfigStorage';
import {
  applyExtractInterfaceToGraph,
  buildAddGhostNodeAction,
  buildAgentHandoffPrompt,
  buildNavigateToSourceAction,
  buildOpenFileFromAgentAction,
  buildReactFlowNodesAndEdges,
  buildRunOpenAiAgentAction,
  buildSaveBoundaryRuleAction,
  CanvasIncomingState,
  createNextGhostSpecNode,
  reduceIncomingIdeMessage
} from '../components/canvas/canvasActions';
import { AgentConfigForm } from '../components/canvas/AgentConfigForm';
import { AgentTimelineStep } from '../components/canvas/AgentTimelineStep';
import { AgentWorkspacePanel } from '../components/canvas/AgentWorkspacePanel';
import { BoundaryRuleModal } from '../components/canvas/BoundaryRuleModal';
import { CanvasEmptyOverlay } from '../components/canvas/CanvasEmptyOverlay';
import { ArcCanvas } from '../components/ArcCanvas';
import { C4NodeCard } from '../components/C4NodeCard';
import { ConstraintEdge } from '../components/ConstraintEdge';
import { SpecToolbar } from '../components/SpecToolbar';
import { ArcGraph, ArcNode } from '../types/bridge';

const serviceNode: ArcNode = {
  id: 'com.acme.service.OrderService',
  label: 'OrderService',
  c4Level: 'L3_COMPONENT',
  kind: 'CLASS',
  stereotypes: [],
  members: [{ name: 'placeOrder', kind: 'METHOD', typeOrSignature: '(): Unit', visibility: 'public' }],
  contracts: [],
  diffStatus: 'UNCHANGED',
  source: {
    filePath: 'src/main/kotlin/com/acme/service/OrderService.kt',
    startLine: 10,
    endLine: 40,
    language: 'kotlin'
  }
};

const controllerNode: ArcNode = {
  id: 'com.acme.controller.OrderController',
  label: 'OrderController',
  c4Level: 'L3_COMPONENT',
  kind: 'REST_CONTROLLER',
  stereotypes: [],
  members: [],
  contracts: [],
  diffStatus: 'VIOLATION',
  violations: ['Illegal direct repo access']
};

const ghostNode: ArcNode = {
  id: 'com.acme.spec.PlannedComponent1',
  label: 'PlannedComponent1',
  c4Level: 'L3_COMPONENT',
  kind: 'GHOST_SPEC',
  stereotypes: ['«Ghost Spec»'],
  members: [{ name: 'execute', kind: 'METHOD', typeOrSignature: '(): Result', visibility: 'public' }],
  contracts: [],
  diffStatus: 'ADDED'
};

const sampleGraph: ArcGraph = {
  projectName: 'acme-orders',
  nodes: [controllerNode, serviceNode, ghostNode],
  edges: [
    {
      id: 'e1',
      sourceId: controllerNode.id,
      targetId: serviceNode.id,
      kind: 'CALLS',
      label: 'calls',
      weight: 1,
      inCycle: true,
      diffStatus: 'VIOLATION',
      violations: ['Cycle detected']
    }
  ],
  rules: [
    {
      id: 'rule-1',
      name: 'No Controller to Repo',
      fromPattern: '*.controller.*',
      toPattern: '*.repository.*',
      policy: 'FORBIDDEN',
      rationale: 'Keep layers decoupled'
    }
  ],
  metadata: {}
};

describe('canvas/agentWorkspaceHelpers', () => {
  it('loads, saves, and sanitizes stored agent config', () => {
    const store = new Map<string, string>();
    const storage = {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => {
        store.set(k, v);
      }
    };

    expect(loadStoredAgentConfig(storage)).toEqual({
      baseUrl: DEFAULT_AGENT_BASE_URL,
      model: '',
      apiKey: '',
      maxTurns: DEFAULT_AGENT_MAX_TURNS
    });

    expect(sanitizeMaxTurns(0)).toBe(DEFAULT_AGENT_MAX_TURNS);
    expect(sanitizeMaxTurns(250)).toBe(100);
    expect(sanitizeMaxTurns(12)).toBe(12);

    saveStoredAgentConfig(
      {
        baseUrl: 'http://localhost:8080/v1',
        model: 'qwen2.5-coder:32b',
        apiKey: 'sk-123',
        maxTurns: 150
      },
      storage
    );

    expect(loadStoredAgentConfig(storage)).toEqual({
      baseUrl: 'http://localhost:8080/v1',
      model: 'qwen2.5-coder:32b',
      apiKey: '',
      maxTurns: 100
    });
    expect(store.has('arcdrift.agentApiKey')).toBe(false);
  });

  it('builds structured handoff prompt from violations, cycles, ghost specs, and rules', () => {
    const prompt = buildAgentHandoffPrompt(sampleGraph);
    expect(prompt).toContain('Cycle detected');
    expect(prompt).toContain('Illegal direct repo access');
    expect(prompt).toContain('Break the following 1 circular dependency edge');
    expect(prompt).toContain('Implement 1 planned Ghost Spec component');
    expect(prompt).toContain('PlannedComponent1');
    expect(prompt).toContain('Respect the 1 architecture.arc.mmd boundary rule');
  });

  it('applies extract interface transform immutably and rewires incoming CALLS/DEPENDS_ON edges', () => {
    const { nextGraph, interfaceName, pkg } = applyExtractInterfaceToGraph(sampleGraph, serviceNode);
    expect(interfaceName).toBe('OrderServicePort');
    expect(pkg).toBe('com.acme.service');

    const portNode = nextGraph.nodes.find((n) => n.id === 'com.acme.service.OrderServicePort');
    expect(portNode).toBeDefined();
    expect(portNode?.kind).toBe('INTERFACE');
    expect(portNode?.source?.filePath).toBe('src/main/kotlin/com/acme/service/OrderServicePort.kt');

    const rewiredEdge = nextGraph.edges.find((e) => e.id === 'e1');
    expect(rewiredEdge?.targetId).toBe('com.acme.service.OrderServicePort');
    expect(rewiredEdge?.diffStatus).toBe('MODIFIED');

    const implEdge = nextGraph.edges.find((e) => e.id === 'impl-OrderService-OrderServicePort');
    expect(implEdge?.kind).toBe('IMPLEMENTS');

    // Calling again is a no-op
    const second = applyExtractInterfaceToGraph(nextGraph, serviceNode);
    expect(second.nextGraph.nodes.length).toBe(nextGraph.nodes.length);

    // Extracting an interface from a nested class (`Outer.Inner`) uses `parentId` as the target package
    const nestedNode: ArcNode = {
      ...serviceNode,
      id: 'com.acme.service.OuterClass.InnerService',
      label: 'InnerService',
      parentId: 'com.acme.service'
    };
    const nestedRes = applyExtractInterfaceToGraph(
      { ...sampleGraph, nodes: [...sampleGraph.nodes, nestedNode] },
      nestedNode
    );
    expect(nestedRes.pkg).toBe('com.acme.service');
    expect(nestedRes.nextGraph.nodes.some((n) => n.id === 'com.acme.service.InnerServicePort')).toBe(true);
  });
});

describe('canvas UI components', () => {
  it('renders AgentConfigForm with discovered model selector and PasswordSafe placeholder', () => {
    const html = renderToStaticMarkup(
      <AgentConfigForm
        baseUrl="http://localhost:11434"
        model="qwen2.5-coder:32b"
        models={['qwen2.5-coder:32b', 'llama3.3:70b']}
        apiKey=""
        hasSavedApiKey={true}
        maxTurns={30}
        promptText="Fix violations"
        onChangeBaseUrl={vi.fn()}
        onChangeModel={vi.fn()}
        onChangeApiKey={vi.fn()}
        onChangeMaxTurns={vi.fn()}
        onChangePromptText={vi.fn()}
        onFetchModels={vi.fn()}
      />
    );
    expect(html).toContain('OpenAI API Base URL');
    expect(html).toContain('Fetch Models');
    expect(html).toContain('Models (2)');
    expect(html).toContain('qwen2.5-coder:32b');
    expect(html).toContain('Saved in IDE PasswordSafe');
  });

  it('renders AgentTimelineStep for turn, tool_call with FileChip, and assistant markdown', () => {
    const turnHtml = renderToStaticMarkup(
      <AgentTimelineStep
        step={{ kind: 'turn', title: 'Turn 1 / 30', detail: '', turn: 1, maxTurns: 30, done: false, success: true }}
        index={0}
        nodes={sampleGraph.nodes}
        onOpenFile={vi.fn()}
      />
    );
    expect(turnHtml).toContain('Turn 1 / 30');

    const toolHtml = renderToStaticMarkup(
      <AgentTimelineStep
        step={{
          kind: 'tool_call',
          title: 'read_project_file (src/main/kotlin/com/acme/service/OrderService.kt)',
          detail: '{"filePath":"src/main/kotlin/com/acme/service/OrderService.kt"}',
          turn: 1,
          maxTurns: 30,
          done: false,
          success: true
        }}
        index={1}
        nodes={sampleGraph.nodes}
        onOpenFile={vi.fn()}
      />
    );
    expect(toolHtml).toContain('read_project_file');
    expect(toolHtml).toContain('OrderService.kt');
    expect(toolHtml).toContain('View tool arguments');
  });

  it('renders AgentWorkspacePanel and CanvasEmptyOverlay', () => {
    const panelHtml = renderToStaticMarkup(
      <AgentWorkspacePanel
        panelWide={false}
        configExpanded={false}
        running={false}
        baseUrl="http://localhost:11434"
        model=""
        models={[]}
        apiKey=""
        maxTurns={25}
        promptText=""
        steps={[]}
        nodes={sampleGraph.nodes}
        timelineEndRef={{ current: null }}
        onToggleConfigExpanded={vi.fn()}
        onTogglePanelWide={vi.fn()}
        onClearSteps={vi.fn()}
        onClose={vi.fn()}
        onChangeBaseUrl={vi.fn()}
        onChangeModel={vi.fn()}
        onChangeApiKey={vi.fn()}
        onChangeMaxTurns={vi.fn()}
        onChangePromptText={vi.fn()}
        onFetchModels={vi.fn()}
        onRunAgent={vi.fn()}
        onCancelAgent={vi.fn()}
        onOpenFile={vi.fn()}
      />
    );
    expect(panelHtml).toContain('Agent Workspace');
    expect(panelHtml).toContain('IDLE');
    expect(panelHtml).toContain('Run Self-Healing Agent (25 max turns)');

    const emptyHtml = renderToStaticMarkup(
      <CanvasEmptyOverlay onlyDiffAndBlast={true} totalNodes={3} onResetFilters={vi.fn()} />
    );
    expect(emptyHtml).toContain('No Diff or Boundary Violations in Current View');
    expect(emptyHtml).toContain('Show All 3 Nodes');

    const runningPanelHtml = renderToStaticMarkup(
      <AgentWorkspacePanel
        panelWide={true}
        configExpanded={true}
        running={true}
        baseUrl="http://localhost:11434"
        model="qwen2.5-coder:32b"
        models={['qwen2.5-coder:32b']}
        apiKey=""
        hasSavedApiKey={true}
        maxTurns={25}
        promptText="Fix"
        steps={[
          { kind: 'status', title: 'Step 1', detail: 'Working', turn: 1, maxTurns: 25, done: false, success: true }
        ]}
        nodes={sampleGraph.nodes}
        timelineEndRef={{ current: null }}
        onToggleConfigExpanded={vi.fn()}
        onTogglePanelWide={vi.fn()}
        onClearSteps={vi.fn()}
        onClose={vi.fn()}
        onChangeBaseUrl={vi.fn()}
        onChangeModel={vi.fn()}
        onChangeApiKey={vi.fn()}
        onChangeMaxTurns={vi.fn()}
        onChangePromptText={vi.fn()}
        onFetchModels={vi.fn()}
        onRunAgent={vi.fn()}
        onCancelAgent={vi.fn()}
        onOpenFile={vi.fn()}
      />
    );
    expect(runningPanelHtml).toContain('RUNNING');
    expect(runningPanelHtml).toContain('Stop Agent');

    const idleWithStepsHtml = renderToStaticMarkup(
      <AgentWorkspacePanel
        panelWide={false}
        configExpanded={false}
        running={false}
        baseUrl="http://localhost:11434"
        model=""
        models={[]}
        apiKey=""
        maxTurns={25}
        promptText=""
        steps={[
          { kind: 'complete', title: 'Done', detail: 'OK', turn: 1, maxTurns: 25, done: true, success: true }
        ]}
        nodes={sampleGraph.nodes}
        timelineEndRef={{ current: null }}
        onToggleConfigExpanded={vi.fn()}
        onTogglePanelWide={vi.fn()}
        onClearSteps={vi.fn()}
        onClose={vi.fn()}
        onChangeBaseUrl={vi.fn()}
        onChangeModel={vi.fn()}
        onChangeApiKey={vi.fn()}
        onChangeMaxTurns={vi.fn()}
        onChangePromptText={vi.fn()}
        onFetchModels={vi.fn()}
        onRunAgent={vi.fn()}
        onCancelAgent={vi.fn()}
        onOpenFile={vi.fn()}
      />
    );
    expect(idleWithStepsHtml).toContain('Clear');
    expect(idleWithStepsHtml).toContain('1 activity event recorded');

    // Verify pure action builders
    expect(buildNavigateToSourceAction(serviceNode).message.type).toBe('NAVIGATE_TO_SOURCE');
    expect(
      buildOpenFileFromAgentAction({ filePath: 'OrderService.kt', line: 12 }).toast
    ).toContain('OrderService.kt:12');
    const rf = buildReactFlowNodesAndEdges(
      sampleGraph,
      { [serviceNode.id]: { x: 100, y: 200 } },
      { [controllerNode.id]: { x: 40, y: 60 } },
      true,
      vi.fn(),
      vi.fn()
    );
    expect(rf.nodes).toHaveLength(3);
    expect(rf.edges).toHaveLength(1);
    expect(buildAddGhostNodeAction(sampleGraph, true).ghost.id).toBe('com.acme.spec.PlannedComponent2');
    expect(
      buildSaveBoundaryRuleAction(sampleGraph, {
        id: 'rule-2',
        name: 'Rule 2',
        fromPattern: 'a',
        toPattern: 'b',
        policy: 'ALLOWED_ONLY',
        rationale: 'r'
      }).nextGraph.rules
    ).toHaveLength(2);
    expect(
      buildRunOpenAiAgentAction(sampleGraph, 'Fix', 'http://localhost:11434', '', '', 30).message.type
    ).toBe('HANDOFF_TO_AGENT');
  });

  it('renders BoundaryRuleModal with active guardrails and SpecToolbar with Refresh action', () => {
    const modalHtml = renderToStaticMarkup(
      <BoundaryRuleModal
        existingRules={sampleGraph.rules}
        onSaveRule={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(modalHtml).toContain('Add Boundary Rule');
    expect(modalHtml).toContain('Active Guardrails (1)');
    expect(modalHtml).toContain('rule-1');
    expect(modalHtml).toContain('Save Rule to Spec');

    // Verify BoundaryRuleModal skips colliding rule-${length + 1} IDs
    const nonCollidingModalHtml = renderToStaticMarkup(
      <BoundaryRuleModal
        existingRules={[
          {
            id: 'rule-2',
            name: 'Existing Rule Two',
            fromPattern: '*.a.*',
            toPattern: '*.b.*',
            policy: 'FORBIDDEN',
            rationale: 'test'
          }
        ]}
        onSaveRule={vi.fn()}
        onClose={vi.fn()}
      />
    );
    expect(nonCollidingModalHtml).toContain('value="rule-3"');

    const toolbarHtml = renderToStaticMarkup(
      <SpecToolbar
        projectName="acme-orders"
        zoomLevel="L3_COMPONENT"
        onlyDiffAndBlast={false}
        sandboxPreviewMode={false}
        searchQuery=""
        metrics={computeCanvasMetrics(sampleGraph)}
        onChangeZoomLevel={vi.fn()}
        onToggleDiffFilter={vi.fn()}
        onToggleSandboxMode={vi.fn()}
        onSearchChange={vi.fn()}
        onAddGhostNode={vi.fn()}
        onAddForbiddenRule={vi.fn()}
        onRelayout={vi.fn()}
        onRefresh={vi.fn()}
        onHandoffToAgent={vi.fn()}
      />
    );
    expect(toolbarHtml).toContain('Refresh');
    expect(toolbarHtml).toContain('+ Boundary Rule');
  });

  it('reduces all IncomingIdeMessage variants and creates non-colliding Ghost Spec nodes', () => {
    const ghost2 = createNextGhostSpecNode(sampleGraph.nodes);
    expect(ghost2.id).toBe('com.acme.spec.PlannedComponent2');

    let state: CanvasIncomingState = {
      graph: sampleGraph,
      statusToast: 'Ready',
      agentBaseUrl: DEFAULT_AGENT_BASE_URL,
      agentModel: '',
      agentModels: [],
      agentApiKey: '',
      agentHasSavedApiKey: false,
      agentMaxTurns: 30,
      agentRunning: true,
      agentSteps: []
    };

    state = reduceIncomingIdeMessage(state, {
      type: 'GRAPH_UPDATED',
      graph: DEMO_FALLBACK_GRAPH
    });
    expect(state.graph.projectName).toBe('commerce-platform');
    expect(state.statusToast).toContain('Graph synchronized');

    state = reduceIncomingIdeMessage(state, {
      type: 'NOTIFICATION',
      level: 'info',
      message: 'Saved spec'
    });
    expect(state.statusToast).toBe('Saved spec');

    state = reduceIncomingIdeMessage(state, {
      type: 'THEME_CHANGED',
      theme: 'dark'
    });
    expect(state.theme).toBe('dark');

    state = reduceIncomingIdeMessage(state, {
      type: 'AGENT_CONFIG_LOADED',
      baseUrl: 'http://localhost:8000',
      model: '',
      apiKey: '',
      hasSavedApiKey: true,
      maxTurns: 40
    });
    expect(state.agentBaseUrl).toBe('http://localhost:8000');
    expect(state.agentHasSavedApiKey).toBe(true);
    expect(state.agentMaxTurns).toBe(40);

    state = reduceIncomingIdeMessage(state, {
      type: 'AGENT_MODELS_LOADED',
      baseUrl: 'http://localhost:8000',
      models: ['qwen2.5-coder:32b', 'llama3.3:70b']
    });
    expect(state.agentModel).toBe('qwen2.5-coder:32b');
    expect(state.autoPersistModel).toBe('qwen2.5-coder:32b');

    state = reduceIncomingIdeMessage(state, {
      type: 'AGENT_MODELS_LOADED',
      baseUrl: 'http://localhost:8000',
      models: [],
      error: 'Connection refused'
    });
    expect(state.agentSteps[state.agentSteps.length - 1].kind).toBe('error');

    state = reduceIncomingIdeMessage(state, {
      type: 'AGENT_STEP',
      step: {
        kind: 'status',
        title: "Model 'old' not found; auto-selected 'llama3.3:70b'",
        detail: '',
        turn: 1,
        maxTurns: 40,
        done: true,
        success: true
      }
    });
    expect(state.agentModel).toBe('llama3.3:70b');
    expect(state.agentRunning).toBe(false);

    state = reduceIncomingIdeMessage(
      { ...state, agentRunning: true },
      { type: 'AGENT_PROGRESS', logLine: 'Completed run', done: true, success: true }
    );
    expect(state.agentRunning).toBe(false);
    expect(state.agentSteps[state.agentSteps.length - 1].kind).toBe('complete');

    state = reduceIncomingIdeMessage(
      { ...state, agentRunning: true },
      { type: 'AGENT_PROGRESS', logLine: 'Failed run', done: true, success: false }
    );
    expect(state.agentSteps[state.agentSteps.length - 1].kind).toBe('error');
  });

  it('renders C4NodeCard, ConstraintEdge, ArcCanvas, and long AgentTimelineStep variants', () => {
    const richViolationNode: ArcNode = {
      ...controllerNode,
      parentId: 'com.acme.controller',
      stereotypes: ['@RestController'],
      members: [{ name: 'status', kind: 'FIELD', typeOrSignature: 'String', visibility: 'public' }],
      contracts: [
        { protocol: 'REST', methodOrAction: 'GET', pathOrTopic: '/v1/a', role: 'PRODUCER', dtoFields: {} },
        { protocol: 'REST', methodOrAction: 'POST', pathOrTopic: '/v1/b', role: 'PRODUCER', dtoFields: {} },
        { protocol: 'REST', methodOrAction: 'DELETE', pathOrTopic: '/v1/c', role: 'PRODUCER', dtoFields: {} }
      ]
    };

    const cardsHtml = renderToStaticMarkup(
      <ReactFlowProvider>
        <C4NodeCard
          id={richViolationNode.id}
          type="c4Card"
          data={{ arcNode: richViolationNode }}
          selected={false}
          dragging={false}
          draggable={true}
          selectable={true}
          deletable={false}
          isConnectable={true}
          positionAbsoluteX={0}
          positionAbsoluteY={0}
          zIndex={1}
        />
        <C4NodeCard
          id={ghostNode.id}
          type="c4Card"
          data={{ arcNode: ghostNode }}
          selected={false}
          dragging={false}
          draggable={true}
          selectable={true}
          deletable={false}
          isConnectable={true}
          positionAbsoluteX={0}
          positionAbsoluteY={200}
          zIndex={1}
        />
      </ReactFlowProvider>
    );
    expect(cardsHtml).toContain('+1 more endpoint');
    expect(cardsHtml).toContain('status: String');
    expect(cardsHtml).toContain('Extract Port');
    expect(cardsHtml).toContain('PlannedComponent1');

    const edgesHtml = renderToStaticMarkup(
      <ReactFlowProvider>
        <svg>
          <ConstraintEdge
            id="e-viol"
            source="a"
            target="b"
            sourceX={100}
            sourceY={50}
            targetX={100}
            targetY={400}
            sourcePosition={Position.Bottom}
            targetPosition={Position.Top}
            data={{
              arcEdge: {
                id: 'e-viol',
                sourceId: 'a',
                targetId: 'b',
                kind: 'FORBIDDEN_RULE',
                label: '[no-controller-to-repo] Controllers must not call repositories directly',
                weight: 1,
                inCycle: false,
                diffStatus: 'VIOLATION'
              }
            }}
          />
          <ConstraintEdge
            id="e-cycle"
            source="b"
            target="a"
            sourceX={100}
            sourceY={100}
            targetX={100}
            targetY={200}
            sourcePosition={Position.Bottom}
            targetPosition={Position.Top}
            data={{
              arcEdge: {
                id: 'e-cycle',
                sourceId: 'b',
                targetId: 'a',
                kind: 'HTTP_CONTRACT',
                label: '',
                weight: 1,
                inCycle: true,
                diffStatus: 'UNCHANGED'
              }
            }}
          />
        </svg>
      </ReactFlowProvider>
    );
    expect(edgesHtml).toContain('svg');

    const longText = 'x'.repeat(300);
    const stepsHtml = renderToStaticMarkup(
      <div>
        <AgentTimelineStep
          step={{
            kind: 'tool_result',
            title: 'Result: read_project_file',
            detail: longText,
            turn: 1,
            maxTurns: 30,
            done: false,
            success: true
          }}
          index={1}
          nodes={sampleGraph.nodes}
          onOpenFile={vi.fn()}
        />
        <AgentTimelineStep
          step={{
            kind: 'tool_result',
            title: 'Result: verify_architectural_compliance',
            detail: longText,
            turn: 1,
            maxTurns: 30,
            done: false,
            success: true
          }}
          index={2}
          nodes={sampleGraph.nodes}
          onOpenFile={vi.fn()}
        />
        <AgentTimelineStep
          step={{
            kind: 'complete',
            title: 'All checks passed',
            detail: '0 violations remain.',
            turn: 2,
            maxTurns: 30,
            done: true,
            success: true
          }}
          index={3}
          nodes={sampleGraph.nodes}
          onOpenFile={vi.fn()}
        />
        <AgentTimelineStep
          step={{
            kind: 'error',
            title: 'Endpoint error',
            detail: 'Connection failed.',
            turn: 2,
            maxTurns: 30,
            done: true,
            success: false
          }}
          index={4}
          nodes={sampleGraph.nodes}
          onOpenFile={vi.fn()}
        />
      </div>
    );
    expect(stepsHtml).toContain('Show full tool output (300 chars)');
    expect(stepsHtml).toContain('All checks passed');
    expect(stepsHtml).toContain('Endpoint error');

    const canvasHtml = renderToStaticMarkup(<ArcCanvas />);
    expect(canvasHtml).toContain('commerce-platform');
    expect(canvasHtml).toContain('Active Guardrail');

    // Verify applyExtractInterfaceToGraph filters out FIELD and private members and only replaces trailing filename (not matching directory segment)
    const dirCollisionNode = {
      ...sampleGraph.nodes[1],
      label: 'Service',
      members: [
        { name: 'repo', kind: 'FIELD', typeOrSignature: 'OrderRepository', visibility: 'private' },
        { name: 'placeOrder', kind: 'METHOD', typeOrSignature: '(id: String): OrderDto', visibility: 'public' },
        { name: 'internalAudit', kind: 'METHOD', typeOrSignature: '(): Unit', visibility: 'internal' },
        { name: 'protectedHook', kind: 'METHOD', typeOrSignature: '(): Unit', visibility: 'protected' },
        { name: 'helper', kind: 'METHOD', typeOrSignature: '(): Unit', visibility: 'private' }
      ],
      source: {
        filePath: 'src/main/kotlin/com/acme/Service/Service.kt',
        startLine: 1,
        endLine: 25,
        language: 'kotlin'
      }
    };
    const extractedRes = applyExtractInterfaceToGraph(
      { ...sampleGraph, nodes: [sampleGraph.nodes[0], dirCollisionNode, sampleGraph.nodes[2]] },
      dirCollisionNode
    );
    const portNode = extractedRes.nextGraph.nodes.find((n) => n.label === 'ServicePort');
    expect(portNode).toBeDefined();
    expect(portNode?.members.map((m) => m.name)).toEqual(['placeOrder']);
    expect(portNode?.source?.filePath).toBe('src/main/kotlin/com/acme/Service/ServicePort.kt');
  });
});
