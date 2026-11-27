export type C4Level = 'L1_SYSTEM' | 'L2_CONTAINER' | 'L3_COMPONENT' | 'L4_CODE';

export type NodeKind =
  | 'SYSTEM'
  | 'MODULE'
  | 'PACKAGE'
  | 'SERVICE_BEAN'
  | 'REST_CONTROLLER'
  | 'REPOSITORY'
  | 'ENTITY'
  | 'MESSAGE_LISTENER'
  | 'CLASS'
  | 'INTERFACE'
  | 'DATA_CLASS'
  | 'ENUM'
  | 'TS_CLIENT'
  | 'PYTHON_CONSUMER'
  | 'GHOST_SPEC';

export type DiffStatus =
  | 'UNCHANGED'
  | 'ADDED'
  | 'MODIFIED'
  | 'REMOVED'
  | 'VIOLATION'
  | 'BLAST_IMPACTED';

export type RulePolicy = 'FORBIDDEN' | 'ALLOWED_ONLY' | 'MUST_DEPEND_VIA_INTERFACE';

export interface SourceLocation {
  filePath: string;
  startLine: number;
  endLine: number;
  language: string;
}

export interface MemberSignature {
  name: string;
  kind: string;
  typeOrSignature: string;
  visibility: string;
}

export interface ContractEndpoint {
  protocol: string;
  methodOrAction: string;
  pathOrTopic: string;
  role: string;
  requestDto?: string;
  responseDto?: string;
  dtoFields?: Record<string, string>;
  requestDtoFields?: Record<string, string>;
}

export interface ArcNode {
  id: string;
  label: string;
  c4Level: C4Level;
  kind: NodeKind;
  parentId?: string | null;
  stereotypes: string[];
  members: MemberSignature[];
  contracts: ContractEndpoint[];
  source?: SourceLocation | null;
  diffStatus: DiffStatus;
  violations?: string[];
  layoutX?: number | null;
  layoutY?: number | null;
}

export interface ArcEdge {
  id: string;
  sourceId: string;
  targetId: string;
  kind: string;
  label?: string | null;
  weight: number;
  inCycle: boolean;
  diffStatus: DiffStatus;
  violations?: string[];
}

export interface BoundaryRule {
  id: string;
  name: string;
  fromPattern: string;
  toPattern: string;
  policy: RulePolicy;
  rationale: string;
}

export interface ArcGraph {
  projectName: string;
  nodes: ArcNode[];
  edges: ArcEdge[];
  rules: BoundaryRule[];
  metadata: Record<string, string>;
}

export type RefactoringCommand =
  | { type: 'RENAME_SYMBOL'; targetNodeId: string; newSimpleName: string }
  | { type: 'MOVE_CLASS_OR_PACKAGE'; targetNodeId: string; destinationPackage: string }
  | {
      type: 'EXTRACT_INTERFACE';
      sourceClassId: string;
      interfaceSimpleName: string;
      targetPackage: string;
      memberNames: string[];
    }
  | { type: 'CREATE_NODE'; fqName: string; kind: string; stereotypes: string[] }
  | { type: 'ADD_DEPENDENCY'; sourceClassId: string; targetTypeFqName: string; relationKind: string };

export interface AgentStepEvent {
  kind: 'status' | 'turn' | 'assistant' | 'tool_call' | 'tool_result' | 'complete' | 'error';
  title: string;
  detail: string;
  turn: number;
  maxTurns: number;
  done: boolean;
  success: boolean;
}

export type OutgoingIdeMessage =
  | { type: 'REQUEST_INITIAL_GRAPH' }
  | { type: 'APPLY_REFACTORING'; command: RefactoringCommand; previewOnly: boolean }
  | { type: 'SAVE_SPEC_RULE'; rule: BoundaryRule }
  | { type: 'NAVIGATE_TO_SOURCE'; nodeId: string; filePath?: string; line?: number }
  | { type: 'CHANGE_ZOOM_LEVEL'; level: C4Level }
  | {
      type: 'SAVE_AGENT_CONFIG';
      baseUrl: string;
      model: string;
      apiKey: string;
      maxTurns: number;
    }
  | { type: 'FETCH_AGENT_MODELS'; baseUrl: string; apiKey?: string }
  | { type: 'CANCEL_AGENT_RUN' }
  | {
      type: 'HANDOFF_TO_AGENT';
      specPrompt: string;
      ghostNodes: ArcNode[];
      rules: BoundaryRule[];
      agentBaseUrl?: string;
      agentModel?: string;
      agentApiKey?: string;
      maxTurns?: number;
    };

export type IncomingIdeMessage =
  | { type: 'GRAPH_UPDATED'; graph: ArcGraph }
  | { type: 'DIFF_UPDATED'; graph: ArcGraph; couplingDeltaPercent: number }
  | { type: 'THEME_CHANGED'; theme: 'dark' | 'light' }
  | { type: 'NOTIFICATION'; message: string; level: 'info' | 'warning' | 'error' }
  | {
      type: 'AGENT_CONFIG_LOADED';
      baseUrl: string;
      model: string;
      apiKey: string;
      hasSavedApiKey?: boolean;
      maxTurns: number;
    }
  | { type: 'AGENT_MODELS_LOADED'; models: string[]; baseUrl: string; error?: string }
  | { type: 'AGENT_STEP'; step: AgentStepEvent }
  | { type: 'AGENT_PROGRESS'; logLine: string; done?: boolean; success?: boolean };
