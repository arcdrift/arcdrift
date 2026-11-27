import React from 'react';
import { pluralize } from '../../bridge/ideBridge';
import { AgentStepEvent, ArcNode } from '../../types/bridge';
import {
  AgentMarkdownRenderer,
  extractToolCallFileReferences,
  FileChip,
  OpenFileTarget
} from '../AgentMarkdownRenderer';
import { MONO_FONT } from '../markdown/constants';

export interface AgentTimelineStepProps {
  step: AgentStepEvent;
  index: number;
  nodes: ArcNode[];
  onOpenFile: (target: OpenFileTarget) => void;
}

const rawPreStyle: React.CSSProperties = {
  margin: '6px 0 0 0',
  padding: 8,
  background: '#09090b',
  border: '1px solid #27272a',
  borderRadius: 5,
  fontFamily: MONO_FONT,
  fontSize: 11.5,
  lineHeight: 1.45,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  color: '#d4d4d8',
  overflowY: 'auto'
};

export const AgentTimelineStep: React.FC<AgentTimelineStepProps> = ({
  step,
  index,
  nodes,
  onOpenFile
}) => {
  const isError = step.kind === 'error';
  const isComplete = step.kind === 'complete';
  const isToolCall = step.kind === 'tool_call';
  const isToolResult = step.kind === 'tool_result';
  const isAssistant = step.kind === 'assistant';

  if (step.kind === 'turn') {
    return (
      <div
        style={{
          fontSize: 12,
          fontWeight: 600,
          color: '#a1a1aa',
          borderBottom: '1px solid #27272a',
          paddingBottom: 4,
          marginTop: index > 0 ? 6 : 0
        }}
      >
        {step.title}
      </div>
    );
  }

  const toolFileRefs = isToolCall
    ? extractToolCallFileReferences(step.title, step.detail, nodes)
    : [];
  const cleanToolTitle = isToolCall
    ? step.title.replace(/\s*\([^)]*\)\s*$/, '')
    : step.title;
  const isRawFileReadResult = isToolResult && step.title.includes('read_project_file');

  const headerTitle = isToolCall
    ? `🔧 ${cleanToolTitle}`
    : isComplete
      ? `✅ ${step.title}`
      : isError
        ? `⛔ ${step.title}`
        : step.title;

  return (
    <div
      style={{
        borderRadius: 7,
        border: isError
          ? '1px solid rgba(239, 68, 68, 0.55)'
          : isComplete
            ? '1px solid rgba(59, 130, 246, 0.55)'
            : '1px solid #27272a',
        background: isError
          ? 'rgba(127, 29, 29, 0.22)'
          : isComplete
            ? 'rgba(30, 58, 138, 0.22)'
            : isAssistant
              ? '#141418'
              : '#18181b',
        padding: '9px 12px'
      }}
    >
      <div
        style={{
          fontSize: 12,
          fontWeight: 600,
          color: isError
            ? '#fca5a5'
            : isComplete
              ? '#93c5fd'
              : isToolCall
                ? '#e4e4e7'
                : '#d4d4d8',
          fontFamily: isToolCall || isToolResult ? MONO_FONT : 'inherit',
          marginBottom: step.detail ? 6 : 0,
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 6
        }}
      >
        <span>{headerTitle}</span>
        {toolFileRefs.map((ref) => (
          <FileChip key={ref.filePath} reference={ref} onOpenFile={onOpenFile} />
        ))}
      </div>

      {step.detail && (
        isToolCall ? (
          <details style={{ fontSize: 11.5, color: '#a1a1aa' }}>
            <summary style={{ cursor: 'pointer', color: '#94a3b8', userSelect: 'none' }}>
              View tool arguments
            </summary>
            <pre style={{ ...rawPreStyle, maxHeight: 240 }}>{step.detail}</pre>
          </details>
        ) : isToolResult && step.detail.length > 260 ? (
          <details style={{ fontSize: 12, color: '#a1a1aa' }}>
            <summary style={{ cursor: 'pointer', color: '#94a3b8', userSelect: 'none' }}>
              Show full tool output ({pluralize(step.detail.length, 'char')})
            </summary>
            {isRawFileReadResult ? (
              <pre style={{ ...rawPreStyle, maxHeight: 320 }}>{step.detail}</pre>
            ) : (
              <div
                style={{
                  marginTop: 6,
                  padding: '8px 10px',
                  background: '#09090b',
                  border: '1px solid #27272a',
                  borderRadius: 5,
                  maxHeight: 320,
                  overflowY: 'auto'
                }}
              >
                <AgentMarkdownRenderer
                  content={step.detail}
                  nodes={nodes}
                  onOpenFile={onOpenFile}
                />
              </div>
            )}
          </details>
        ) : (
          <AgentMarkdownRenderer
            content={step.detail}
            nodes={nodes}
            onOpenFile={onOpenFile}
          />
        )
      )}
    </div>
  );
};
