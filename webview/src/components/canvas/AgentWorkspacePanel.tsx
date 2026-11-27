import React from 'react';
import { pluralize } from '../../bridge/ideBridge';
import { AgentStepEvent, ArcNode } from '../../types/bridge';
import { OpenFileTarget } from '../AgentMarkdownRenderer';
import { MONO_FONT } from '../markdown/constants';
import { AgentConfigForm } from './AgentConfigForm';
import { AgentTimelineStep } from './AgentTimelineStep';

export interface AgentWorkspacePanelProps {
  panelWide: boolean;
  configExpanded: boolean;
  running: boolean;
  baseUrl: string;
  model: string;
  models: string[];
  apiKey: string;
  hasSavedApiKey?: boolean;
  maxTurns: number;
  promptText: string;
  steps: AgentStepEvent[];
  nodes: ArcNode[];
  timelineEndRef: React.RefObject<HTMLDivElement>;
  onToggleConfigExpanded: () => void;
  onTogglePanelWide: () => void;
  onClearSteps: () => void;
  onClose: () => void;
  onChangeBaseUrl: (value: string) => void;
  onChangeModel: (value: string) => void;
  onChangeApiKey: (value: string) => void;
  onChangeMaxTurns: (value: number) => void;
  onChangePromptText: (value: string) => void;
  onFetchModels: () => void;
  onRunAgent: () => void;
  onCancelAgent: () => void;
  onOpenFile: (target: OpenFileTarget) => void;
}

export const AgentWorkspacePanel: React.FC<AgentWorkspacePanelProps> = ({
  panelWide,
  configExpanded,
  running,
  baseUrl,
  model,
  models,
  apiKey,
  hasSavedApiKey,
  maxTurns,
  promptText,
  steps,
  nodes,
  timelineEndRef,
  onToggleConfigExpanded,
  onTogglePanelWide,
  onClearSteps,
  onClose,
  onChangeBaseUrl,
  onChangeModel,
  onChangeApiKey,
  onChangeMaxTurns,
  onChangePromptText,
  onFetchModels,
  onRunAgent,
  onCancelAgent,
  onOpenFile
}) => (
  <div
    style={{
      width: panelWide ? '56%' : 480,
      minWidth: 420,
      maxWidth: '72vw',
      height: '100%',
      background: '#121215',
      borderLeft: '1px solid #27272a',
      color: '#f4f4f5',
      display: 'flex',
      flexDirection: 'column',
      flexShrink: 0,
      overflow: 'hidden'
    }}
  >
    <div
      style={{
        height: 44,
        flexShrink: 0,
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        padding: '0 12px',
        background: '#09090b',
        borderBottom: '1px solid #27272a',
        gap: 8,
        whiteSpace: 'nowrap'
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: '#fafafa', whiteSpace: 'nowrap' }}>
          Agent Workspace
        </span>
        <span
          style={{
            fontSize: 12,
            padding: '2px 8px',
            borderRadius: 4,
            background: running ? '#1e3a8a' : '#27272a',
            border: `1px solid ${running ? '#3b82f6' : '#3f3f46'}`,
            color: running ? '#93c5fd' : '#a1a1aa',
            fontFamily: MONO_FONT,
            whiteSpace: 'nowrap'
          }}
        >
          {running ? '● RUNNING' : 'IDLE'}
        </span>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
        <button
          type="button"
          className={`arc-btn ${configExpanded ? 'arc-btn-active' : ''}`}
          onClick={onToggleConfigExpanded}
          style={{ padding: '3px 8px', whiteSpace: 'nowrap' }}
        >
          {configExpanded ? 'Hide Config' : 'Config'}
        </button>
        <button
          type="button"
          className="arc-btn"
          onClick={onTogglePanelWide}
          style={{ padding: '3px 8px', whiteSpace: 'nowrap' }}
        >
          {panelWide ? 'Normal Width' : 'Wide View'}
        </button>
        {steps.length > 0 && !running && (
          <button
            type="button"
            className="arc-btn"
            onClick={onClearSteps}
            style={{ padding: '3px 8px', whiteSpace: 'nowrap' }}
          >
            Clear
          </button>
        )}
        <button
          type="button"
          className="arc-btn"
          onClick={onClose}
          style={{ padding: '3px 8px' }}
        >
          ✕
        </button>
      </div>
    </div>

    {configExpanded && (
      <AgentConfigForm
        baseUrl={baseUrl}
        model={model}
        models={models}
        apiKey={apiKey}
        hasSavedApiKey={hasSavedApiKey}
        maxTurns={maxTurns}
        promptText={promptText}
        onChangeBaseUrl={onChangeBaseUrl}
        onChangeModel={onChangeModel}
        onChangeApiKey={onChangeApiKey}
        onChangeMaxTurns={onChangeMaxTurns}
        onChangePromptText={onChangePromptText}
        onFetchModels={onFetchModels}
      />
    )}

    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        padding: '8px 14px',
        background: '#18181b',
        borderBottom: '1px solid #27272a'
      }}
    >
      <span style={{ fontSize: 12, color: '#a1a1aa' }}>
        {steps.length === 0
          ? 'Ready to dispatch architectural task to agent.'
          : `${pluralize(steps.length, 'activity event')} recorded`}
      </span>
      <div style={{ display: 'flex', gap: 8 }}>
        {running ? (
          <button
            type="button"
            className="arc-btn"
            onClick={onCancelAgent}
            style={{
              background: 'rgba(239, 68, 68, 0.2)',
              borderColor: '#ef4444',
              color: '#fca5a5'
            }}
          >
            ■ Stop Agent
          </button>
        ) : (
          <button
            type="button"
            className="arc-btn arc-btn-primary"
            onClick={onRunAgent}
          >
            ▶ Run Self-Healing Agent ({pluralize(maxTurns, 'max turn', 'max turns')})
          </button>
        )}
      </div>
    </div>

    <div
      style={{
        flex: 1,
        overflowY: 'auto',
        padding: '12px 14px',
        background: '#09090b',
        display: 'flex',
        flexDirection: 'column',
        gap: 10
      }}
    >
      {steps.length === 0 ? (
        <div
          style={{
            color: '#94949e',
            fontSize: 12,
            textAlign: 'center',
            padding: '40px 20px',
            lineHeight: 1.6
          }}
        >
          Configure your OpenAI-compatible endpoint above and click{' '}
          <strong style={{ color: '#d4d4d8' }}>Run Self-Healing Agent</strong>.
          <br />
          Each reasoning step, file read/write, refactoring command, and compliance check
          will appear here in real time.
        </div>
      ) : (
        steps.map((step, idx) => (
          <AgentTimelineStep
            key={idx}
            step={step}
            index={idx}
            nodes={nodes}
            onOpenFile={onOpenFile}
          />
        ))
      )}
      <div ref={timelineEndRef} />
    </div>
  </div>
);
