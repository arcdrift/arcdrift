import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Background,
  BackgroundVariant,
  Controls,
  Edge,
  MiniMap,
  Node,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  AgentStepEvent,
  ArcGraph,
  ArcNode,
  BoundaryRule,
  C4Level,
  IncomingIdeMessage
} from '../types/bridge';
import {
  computeCanvasMetrics,
  DEMO_FALLBACK_GRAPH,
  filterGraphByZoomAndDiff,
  pluralize,
  sendIdeMessage
} from '../bridge/ideBridge';
import { computeElkLayout } from '../layout/elkLayout';
import { C4NodeCard } from './C4NodeCard';
import { ConstraintEdge } from './ConstraintEdge';
import { SpecToolbar } from './SpecToolbar';
import { OpenFileTarget } from './AgentMarkdownRenderer';
import { AgentWorkspacePanel } from './canvas/AgentWorkspacePanel';
import { BoundaryRuleModal } from './canvas/BoundaryRuleModal';
import { CanvasEmptyOverlay } from './canvas/CanvasEmptyOverlay';
import {
  createDebouncedConfigSaver,
  loadStoredAgentConfig,
  saveStoredAgentConfig
} from './canvas/agentConfigStorage';
import {
  applyExtractInterfaceToGraph,
  buildAddGhostNodeAction,
  buildAgentHandoffPrompt,
  buildNavigateToSourceAction,
  buildOpenFileFromAgentAction,
  buildReactFlowNodesAndEdges,
  buildRunOpenAiAgentAction,
  buildSaveBoundaryRuleAction,
  reduceIncomingIdeMessage
} from './canvas/canvasActions';

const nodeTypes = {
  c4Card: C4NodeCard
};

const edgeTypes = {
  constraintEdge: ConstraintEdge
};

const ArcCanvasInner: React.FC = () => {
  const { fitView } = useReactFlow();
  const layoutSeqRef = useRef<number>(0);
  const manualPositionsRef = useRef<Record<string, { x: number; y: number }>>({});
  const timelineEndRef = useRef<HTMLDivElement | null>(null);
  const initialConfig = useMemo(() => loadStoredAgentConfig(), []);

  const [graph, setGraph] = useState<ArcGraph>(DEMO_FALLBACK_GRAPH);
  const [zoomLevel, setZoomLevel] = useState<C4Level>('L3_COMPONENT');
  const [onlyDiffAndBlast, setOnlyDiffAndBlast] = useState<boolean>(false);
  const [sandboxPreviewMode, setSandboxPreviewMode] = useState<boolean>(false);
  const [snapToGrid, setSnapToGrid] = useState<boolean>(true);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [statusToast, setStatusToast] = useState<string>(
    'Ready — Architecture Control Plane active'
  );

  const [agentModalOpen, setAgentModalOpen] = useState<boolean>(false);
  const [ruleModalOpen, setRuleModalOpen] = useState<boolean>(false);
  const [agentPanelWide, setAgentPanelWide] = useState<boolean>(false);
  const [agentConfigExpanded, setAgentConfigExpanded] = useState<boolean>(true);
  const [agentBaseUrl, setAgentBaseUrl] = useState<string>(initialConfig.baseUrl);
  const [agentApiKey, setAgentApiKey] = useState<string>(initialConfig.apiKey);
  const [agentHasSavedApiKey, setAgentHasSavedApiKey] = useState<boolean>(false);
  const [agentMaxTurns, setAgentMaxTurns] = useState<number>(initialConfig.maxTurns);
  const [agentModels, setAgentModels] = useState<string[]>([]);
  const [agentModel, setAgentModel] = useState<string>(initialConfig.model);
  const [agentPromptText, setAgentPromptText] = useState<string>('');
  const [agentRunning, setAgentRunning] = useState<boolean>(false);
  const [agentSteps, setAgentSteps] = useState<AgentStepEvent[]>([]);

  const [rfNodes, setRfNodes, onNodesChange] = useNodesState<Node>([]);
  const [rfEdges, setRfEdges, onEdgesChange] = useEdgesState<Edge>([]);

  const filteredGraph = useMemo(
    () => filterGraphByZoomAndDiff(graph, zoomLevel, onlyDiffAndBlast, searchQuery),
    [graph, zoomLevel, onlyDiffAndBlast, searchQuery]
  );

  const metrics = useMemo(() => computeCanvasMetrics(filteredGraph), [filteredGraph]);

  const handleNavigateToSource = useCallback((node: ArcNode) => {
    const action = buildNavigateToSourceAction(node);
    sendIdeMessage(action.message);
    setStatusToast(action.toast);
  }, []);

  const handleOpenFileFromAgent = useCallback((target: OpenFileTarget) => {
    const action = buildOpenFileFromAgentAction(target);
    sendIdeMessage(action.message);
    setStatusToast(action.toast);
  }, []);

  const sandboxPreviewRef = useRef<boolean>(sandboxPreviewMode);
  sandboxPreviewRef.current = sandboxPreviewMode;

  const agentConfigRef = useRef({
    baseUrl: agentBaseUrl,
    model: agentModel,
    apiKey: agentApiKey,
    maxTurns: agentMaxTurns
  });
  agentConfigRef.current = {
    baseUrl: agentBaseUrl,
    model: agentModel,
    apiKey: agentApiKey,
    maxTurns: agentMaxTurns
  };

  const handleExtractInterface = useCallback((node: ArcNode) => {
    const isPreview = sandboxPreviewRef.current;
    const preview = applyExtractInterfaceToGraph(inboundStateRef.current.graph, node);
    const extractedInterfaceName = preview.interfaceName;
    const extractedPkg = preview.pkg;
    const extractableMethodNames = node.members
      .filter((m) => m.kind === 'METHOD' && m.visibility === 'public')
      .map((m) => m.name);
    inboundStateRef.current.graph = preview.nextGraph;
    setGraph((prev) => applyExtractInterfaceToGraph(prev, node).nextGraph);
    sendIdeMessage({
      type: 'APPLY_REFACTORING',
      previewOnly: isPreview,
      command: {
        type: 'EXTRACT_INTERFACE',
        sourceClassId: node.id,
        interfaceSimpleName: extractedInterfaceName,
        targetPackage: extractedPkg,
        memberNames: extractableMethodNames
      }
    });
    setStatusToast(
      `${isPreview ? '[Sandbox Preview]' : '[Applied to Code & Explorer]'} Extracted interface '${extractedInterfaceName}' from '${node.label}'`
    );
  }, []);

  const rebuildFlowLayout = useCallback(
    async (respectManualPositions: boolean) => {
      if (!respectManualPositions) {
        manualPositionsRef.current = {};
      }
      const seq = ++layoutSeqRef.current;
      const positioned = await computeElkLayout(filteredGraph, respectManualPositions);
      if (seq !== layoutSeqRef.current) return;

      const { nodes: nextNodes, edges: nextEdges } = buildReactFlowNodesAndEdges(
        filteredGraph,
        positioned.positions,
        manualPositionsRef.current,
        respectManualPositions,
        handleNavigateToSource,
        handleExtractInterface
      );

      setRfNodes(nextNodes);
      setRfEdges(nextEdges);

      window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
          if (seq === layoutSeqRef.current) {
            void fitView({ padding: 0.18, duration: 160 });
          }
        });
      });
    },
    [filteredGraph, fitView, handleExtractInterface, handleNavigateToSource, setRfEdges, setRfNodes]
  );

  useEffect(() => {
    void rebuildFlowLayout(true);
  }, [rebuildFlowLayout]);

  useEffect(() => {
    timelineEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [agentSteps]);

  const configSaverRef = useRef(
    createDebouncedConfigSaver((sanitized) => {
      sendIdeMessage({
        type: 'SAVE_AGENT_CONFIG',
        baseUrl: sanitized.baseUrl,
        model: sanitized.model,
        apiKey: sanitized.apiKey,
        maxTurns: sanitized.maxTurns
      });
    }, 300)
  );

  const persistAgentConfig = useCallback(
    (
      nextBaseUrl: string,
      nextModel: string,
      nextApiKey: string,
      nextMaxTurns: number,
      immediate: boolean = false
    ) => {
      configSaverRef.current.schedule(
        {
          baseUrl: nextBaseUrl,
          model: nextModel,
          apiKey: nextApiKey,
          maxTurns: nextMaxTurns
        },
        immediate
      );
    },
    []
  );

  useEffect(() => {
    const saver = configSaverRef.current;
    return () => saver.cancel();
  }, []);

  const inboundStateRef = useRef({
    graph,
    statusToast,
    agentBaseUrl,
    agentModel,
    agentModels,
    agentApiKey,
    agentHasSavedApiKey,
    agentMaxTurns,
    agentRunning,
    agentSteps
  });
  inboundStateRef.current = {
    graph,
    statusToast,
    agentBaseUrl,
    agentModel,
    agentModels,
    agentApiKey,
    agentHasSavedApiKey,
    agentMaxTurns,
    agentRunning,
    agentSteps
  };

  useEffect(() => {
    const receiveHandler = (message: IncomingIdeMessage) => {
      const prev = inboundStateRef.current;
      const reduced = reduceIncomingIdeMessage(prev, message);
      inboundStateRef.current = {
        graph: reduced.graph,
        statusToast: reduced.statusToast,
        agentBaseUrl: reduced.agentBaseUrl,
        agentModel: reduced.autoPersistModel ?? reduced.agentModel,
        agentModels: reduced.agentModels,
        agentApiKey: reduced.agentApiKey,
        agentHasSavedApiKey: reduced.agentHasSavedApiKey,
        agentMaxTurns: reduced.agentMaxTurns,
        agentRunning: reduced.agentRunning,
        agentSteps: reduced.agentSteps
      };
      if (reduced.graph !== prev.graph) setGraph(reduced.graph);
      if (reduced.statusToast !== prev.statusToast) setStatusToast(reduced.statusToast);
      if (reduced.theme) document.documentElement.dataset.theme = reduced.theme;
      if (reduced.saveLoadedConfig) {
        setAgentBaseUrl(reduced.agentBaseUrl);
        setAgentModel(reduced.agentModel);
        setAgentApiKey(reduced.agentApiKey);
        setAgentHasSavedApiKey(reduced.agentHasSavedApiKey);
        setAgentMaxTurns(reduced.agentMaxTurns);
        saveStoredAgentConfig(reduced.saveLoadedConfig);
      }
      if (reduced.agentModels !== prev.agentModels) setAgentModels(reduced.agentModels);
      if (reduced.autoPersistModel) {
        setAgentModel(reduced.autoPersistModel);
        persistAgentConfig(
          reduced.agentBaseUrl,
          reduced.autoPersistModel,
          reduced.agentApiKey,
          reduced.agentMaxTurns,
          true
        );
      }
      if (reduced.agentSteps !== prev.agentSteps) setAgentSteps(reduced.agentSteps);
      if (reduced.agentRunning !== prev.agentRunning) setAgentRunning(reduced.agentRunning);
    };
    window.__arcdriftReceiveMessage = receiveHandler;
    if (
      typeof window !== 'undefined' &&
      Array.isArray(window.__arcdriftPendingInbound) &&
      window.__arcdriftPendingInbound.length > 0
    ) {
      const queued = window.__arcdriftPendingInbound.splice(0);
      for (const msg of queued) {
        receiveHandler(msg);
      }
    }
    return () => {
      if (window.__arcdriftReceiveMessage === receiveHandler) {
        delete window.__arcdriftReceiveMessage;
      }
    };
  }, [persistAgentConfig]);

  useEffect(() => {
    sendIdeMessage({ type: 'REQUEST_INITIAL_GRAPH' });
  }, []);

  const handleAddGhostNode = useCallback(() => {
    const action = buildAddGhostNodeAction(inboundStateRef.current.graph, sandboxPreviewRef.current);
    inboundStateRef.current.graph = action.nextGraph;
    inboundStateRef.current.statusToast = action.toast;
    setGraph(action.nextGraph);
    sendIdeMessage(action.message);
    setStatusToast(action.toast);
  }, []);

  const handleSaveBoundaryRule = useCallback((newRule: BoundaryRule) => {
    const action = buildSaveBoundaryRuleAction(inboundStateRef.current.graph, newRule);
    inboundStateRef.current.graph = action.nextGraph;
    inboundStateRef.current.statusToast = action.toast;
    setGraph(action.nextGraph);
    sendIdeMessage(action.message);
    setRuleModalOpen(false);
    setStatusToast(action.toast);
  }, []);

  const handleHandoffToAgent = useCallback(() => {
    setAgentPromptText(buildAgentHandoffPrompt(graph));
    setAgentModalOpen(true);
    if (agentModels.length === 0) {
      sendIdeMessage({
        type: 'FETCH_AGENT_MODELS',
        baseUrl: agentBaseUrl,
        apiKey: agentApiKey
      });
    }
  }, [agentApiKey, agentBaseUrl, agentModels.length, graph]);

  const handleRunOpenAiAgent = useCallback(() => {
    const action = buildRunOpenAiAgentAction(
      inboundStateRef.current.graph,
      agentPromptText,
      agentBaseUrl,
      agentModel,
      agentApiKey,
      agentMaxTurns
    );
    persistAgentConfig(agentBaseUrl, agentModel, agentApiKey, action.sanitizedTurns, true);
    inboundStateRef.current.agentRunning = true;
    inboundStateRef.current.agentSteps = [action.initialStep];
    setAgentRunning(true);
    setAgentSteps([action.initialStep]);
    sendIdeMessage(action.message);
  }, [agentApiKey, agentBaseUrl, agentMaxTurns, agentModel, agentPromptText, persistAgentConfig]);

  const handleCancelAgent = useCallback(() => {
    sendIdeMessage({ type: 'CANCEL_AGENT_RUN' });
    if (typeof window === 'undefined' || typeof window.__arcdriftPostMessage !== 'function') {
      inboundStateRef.current.agentRunning = false;
      setAgentRunning(false);
    }
    inboundStateRef.current.statusToast = 'Stopping agent run...';
    setStatusToast('Stopping agent run...');
  }, []);

  return (
    <div style={{ width: '100vw', height: '100vh', display: 'flex', flexDirection: 'column', background: '#09090b' }}>
      <SpecToolbar
        projectName={graph.projectName}
        zoomLevel={zoomLevel}
        onlyDiffAndBlast={onlyDiffAndBlast}
        sandboxPreviewMode={sandboxPreviewMode}
        snapToGrid={snapToGrid}
        searchQuery={searchQuery}
        metrics={metrics}
        onChangeZoomLevel={(lvl) => {
          setZoomLevel(lvl);
          sendIdeMessage({ type: 'CHANGE_ZOOM_LEVEL', level: lvl });
        }}
        onToggleDiffFilter={() => setOnlyDiffAndBlast((v) => !v)}
        onToggleSandboxMode={() => setSandboxPreviewMode((v) => !v)}
        onToggleSnapToGrid={() => setSnapToGrid((v) => !v)}
        onSearchChange={setSearchQuery}
        onAddGhostNode={handleAddGhostNode}
        onAddForbiddenRule={() => setRuleModalOpen(true)}
        onRelayout={() => void rebuildFlowLayout(false)}
        onRefresh={() => {
          sendIdeMessage({ type: 'REQUEST_INITIAL_GRAPH' });
          setStatusToast('Refreshing architecture graph...');
        }}
        onHandoffToAgent={handleHandoffToAgent}
      />

      <div style={{ flex: 1, display: 'flex', flexDirection: 'row', overflow: 'hidden' }}>
        <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
          <ReactFlow
            nodes={rfNodes}
            edges={rfEdges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onNodeDragStop={(_event, node) => {
              manualPositionsRef.current[node.id] = {
                x: node.position.x,
                y: node.position.y
              };
            }}
            onNodeDoubleClick={(_event, node) => {
              const arcNode = (node.data as { arcNode?: ArcNode })?.arcNode;
              if (arcNode) {
                handleNavigateToSource(arcNode);
              }
            }}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            snapToGrid={snapToGrid}
            snapGrid={[20, 20]}
            fitView
          >
            <Background
              id="grid-minor"
              variant={BackgroundVariant.Lines}
              gap={20}
              lineWidth={1}
              color="rgba(63, 63, 70, 0.35)"
            />
            <Background
              id="grid-major"
              variant={BackgroundVariant.Lines}
              gap={100}
              lineWidth={1}
              color="rgba(82, 82, 91, 0.6)"
            />
            <Controls />
            <MiniMap
              pannable
              zoomable
              maskColor="rgba(9, 9, 11, 0.72)"
              nodeColor={(n) => {
                const arcNode = (n.data as { arcNode?: ArcNode })?.arcNode;
                if (arcNode?.diffStatus === 'VIOLATION') return '#ef4444';
                return '#52525b';
              }}
              style={{ background: '#18181b', border: '1px solid #3f3f46' }}
            />
          </ReactFlow>

          {rfNodes.length === 0 && (
            <CanvasEmptyOverlay
              onlyDiffAndBlast={onlyDiffAndBlast}
              totalNodes={graph.nodes.length}
              onResetFilters={() => {
                setOnlyDiffAndBlast(false);
                setSearchQuery('');
                setZoomLevel('L3_COMPONENT');
              }}
            />
          )}
        </div>

        {agentModalOpen && (
          <AgentWorkspacePanel
            panelWide={agentPanelWide}
            configExpanded={agentConfigExpanded}
            running={agentRunning}
            baseUrl={agentBaseUrl}
            model={agentModel}
            models={agentModels}
            apiKey={agentApiKey}
            hasSavedApiKey={agentHasSavedApiKey}
            maxTurns={agentMaxTurns}
            promptText={agentPromptText}
            steps={agentSteps}
            nodes={graph.nodes}
            timelineEndRef={timelineEndRef}
            onToggleConfigExpanded={() => setAgentConfigExpanded((v) => !v)}
            onTogglePanelWide={() => setAgentPanelWide((w) => !w)}
            onClearSteps={() => {
              inboundStateRef.current.agentSteps = [];
              setAgentSteps([]);
            }}
            onClose={() => setAgentModalOpen(false)}
            onChangeBaseUrl={(v) => {
              inboundStateRef.current.agentBaseUrl = v;
              setAgentBaseUrl(v);
              persistAgentConfig(v, agentModel, agentApiKey, agentMaxTurns);
            }}
            onChangeModel={(v) => {
              inboundStateRef.current.agentModel = v;
              setAgentModel(v);
              persistAgentConfig(agentBaseUrl, v, agentApiKey, agentMaxTurns);
            }}
            onChangeApiKey={(v) => {
              inboundStateRef.current.agentApiKey = v;
              setAgentApiKey(v);
              persistAgentConfig(agentBaseUrl, agentModel, v, agentMaxTurns);
            }}
            onChangeMaxTurns={(v) => {
              inboundStateRef.current.agentMaxTurns = v;
              setAgentMaxTurns(v);
              persistAgentConfig(agentBaseUrl, agentModel, agentApiKey, v);
            }}
            onChangePromptText={setAgentPromptText}
            onFetchModels={() => {
              persistAgentConfig(agentBaseUrl, agentModel, agentApiKey, agentMaxTurns, true);
              sendIdeMessage({
                type: 'FETCH_AGENT_MODELS',
                baseUrl: agentBaseUrl,
                apiKey: agentApiKey
              });
            }}
            onRunAgent={handleRunOpenAiAgent}
            onCancelAgent={handleCancelAgent}
            onOpenFile={handleOpenFileFromAgent}
          />
        )}
      </div>

      {ruleModalOpen && (
        <BoundaryRuleModal
          existingRules={graph.rules}
          onSaveRule={handleSaveBoundaryRule}
          onClose={() => setRuleModalOpen(false)}
        />
      )}

      <div
        style={{
          padding: '6px 14px',
          background: '#09090b',
          borderTop: '1px solid #27272a',
          color: '#a1a1aa',
          fontSize: 12,
          display: 'flex',
          justifyContent: 'space-between'
        }}
      >
        <span>{statusToast}</span>
        <span>
          {pluralize(graph.rules.length, 'Active Guardrail')} · Double-click any node to open source in IDE
        </span>
      </div>
    </div>
  );
};

export const ArcCanvas: React.FC = () => (
  <ReactFlowProvider>
    <ArcCanvasInner />
  </ReactFlowProvider>
);
