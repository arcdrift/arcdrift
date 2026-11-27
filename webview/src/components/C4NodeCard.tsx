import React from 'react';
import { Handle, NodeProps, Position } from '@xyflow/react';
import { ArcNode } from '../types/bridge';
import { diffStatusBadgeColor, pluralize } from '../bridge/ideBridge';

export interface C4NodeData extends Record<string, unknown> {
  arcNode: ArcNode;
  onNavigateToSource?: (node: ArcNode) => void;
  onExtractInterface?: (node: ArcNode) => void;
}

const MONO_FONT =
  "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace";

export const C4NodeCard: React.FC<NodeProps> = ({ data }) => {
  const payload = data as unknown as C4NodeData;
  const { arcNode, onNavigateToSource, onExtractInterface } = payload;
  const badge = diffStatusBadgeColor(arcNode.diffStatus);
  const isGhost = arcNode.kind === 'GHOST_SPEC';
  const isViolation = arcNode.diffStatus === 'VIOLATION';
  const primaryMember =
    arcNode.members.find((m) => m.kind === 'METHOD') ?? arcNode.members[0];
  const formattedMemberText = primaryMember
    ? primaryMember.typeOrSignature.startsWith('(') ||
      primaryMember.typeOrSignature.startsWith(':') ||
      primaryMember.typeOrSignature.startsWith(' ')
      ? `${primaryMember.name}${primaryMember.typeOrSignature}`
      : `${primaryMember.name}: ${primaryMember.typeOrSignature}`
    : '';
  const canExtractPort =
    arcNode.kind === 'CLASS' ||
    arcNode.kind === 'SERVICE_BEAN' ||
    arcNode.kind === 'REPOSITORY' ||
    arcNode.kind === 'REST_CONTROLLER' ||
    arcNode.kind === 'MESSAGE_LISTENER';
  const violationReasons =
    arcNode.violations && arcNode.violations.length > 0
      ? arcNode.violations
      : isViolation
        ? ['Boundary or contract rule violated — inspect highlighted edge.']
        : [];

  return (
    <div
      onDoubleClick={(e) => {
        e.stopPropagation();
        onNavigateToSource?.(arcNode);
      }}
      style={{
        width: 300,
        minHeight: 124,
        borderRadius: 8,
        border: isGhost
          ? '1.5px dashed #71717a'
          : isViolation
            ? '1.5px solid #ef4444'
            : `1px solid ${badge.border}`,
        background: badge.bg,
        color: '#f4f4f5',
        padding: '11px 13px',
        boxShadow: isViolation
          ? '0 8px 24px rgba(0, 0, 0, 0.65), 0 0 0 1px rgba(239, 68, 68, 0.25)'
          : '0 6px 18px rgba(0, 0, 0, 0.5)',
        fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        cursor: 'pointer'
      }}
    >
      <Handle
        type="target"
        position={Position.Top}
        style={{ background: isViolation ? '#ef4444' : '#71717a', width: 8, height: 8, border: 'none' }}
      />

      {/* Top row: Kind & Status Badge */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 6
        }}
      >
        <span
          style={{
            fontSize: 12,
            fontWeight: 500,
            color: '#a1a1aa',
            fontFamily: MONO_FONT
          }}
        >
          {arcNode.kind.replace(/_/g, ' ')}
        </span>
        {arcNode.diffStatus !== 'UNCHANGED' && (
          <span
            style={{
              fontSize: 12,
              fontWeight: 600,
              color: badge.badgeColor,
              padding: '1px 6px',
              borderRadius: 4,
              background: isViolation ? 'rgba(239, 68, 68, 0.16)' : '#27272a',
              border: isViolation ? '1px solid rgba(239, 68, 68, 0.4)' : '1px solid #3f3f46'
            }}
          >
            {badge.badgeText}
          </span>
        )}
      </div>

      {/* Component Name */}
      <div
        style={{
          fontSize: 15,
          fontWeight: 600,
          color: '#fafafa',
          marginBottom: 4,
          wordBreak: 'break-word',
          letterSpacing: '-0.01em'
        }}
      >
        {arcNode.label}
      </div>

      {/* Package / Stereotypes */}
      {(arcNode.parentId || arcNode.stereotypes.length > 0) && (
        <div
          style={{
            fontSize: 12,
            color: '#a1a1aa',
            fontFamily: MONO_FONT,
            marginBottom: 8,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap'
          }}
          title={[arcNode.parentId, ...arcNode.stereotypes].filter(Boolean).join(' · ')}
        >
          {[arcNode.parentId, ...arcNode.stereotypes].filter(Boolean).join(' · ')}
        </div>
      )}

      {/* Explicit Violation Explanation Callout */}
      {violationReasons.length > 0 && (
        <div
          style={{
            fontSize: 12,
            lineHeight: 1.35,
            background: 'rgba(127, 29, 29, 0.35)',
            border: '1px solid rgba(239, 68, 68, 0.5)',
            color: '#fecaca',
            padding: '6px 8px',
            borderRadius: 5,
            marginBottom: 8
          }}
        >
          {violationReasons.map((reason, idx) => (
            <div key={idx} style={{ marginBottom: idx < violationReasons.length - 1 ? 4 : 0 }}>
              ⛔ {reason}
            </div>
          ))}
        </div>
      )}

      {/* Contract Pill(s) if REST/Kafka */}
      {arcNode.contracts.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 8 }}>
          {arcNode.contracts.slice(0, 2).map((c, idx) => (
            <div
              key={`${c.methodOrAction}-${c.pathOrTopic}-${idx}`}
              style={{
                fontSize: 12,
                fontFamily: MONO_FONT,
                background: '#09090b',
                border: '1px solid #27272a',
                color: '#d4d4d8',
                padding: '4px 8px',
                borderRadius: 5,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap'
              }}
              title={`${c.methodOrAction} ${c.pathOrTopic}`}
            >
              <span style={{ color: '#a1a1aa', fontWeight: 600 }}>
                {c.methodOrAction}
              </span>{' '}
              {c.pathOrTopic}
            </div>
          ))}
          {arcNode.contracts.length > 2 && (
            <div style={{ fontSize: 12, color: '#a1a1aa', fontFamily: MONO_FONT }}>
              +{pluralize(arcNode.contracts.length - 2, 'more endpoint', 'more endpoints')}
            </div>
          )}
        </div>
      )}

      {/* Primary Member Signature */}
      {primaryMember && (
        <div
          style={{
            fontSize: 12,
            color: '#a1a1aa',
            fontFamily: MONO_FONT,
            borderTop: '1px solid #27272a',
            paddingTop: 6,
            marginBottom: 8,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap'
          }}
          title={formattedMemberText}
        >
          {formattedMemberText}
        </div>
      )}

      {/* Quick Action Bar */}
      <div
        className="nodrag nopan"
        style={{
          display: 'flex',
          gap: 6,
          marginTop: 6,
          paddingTop: 6,
          borderTop: '1px solid #27272a'
        }}
      >
        <button
          type="button"
          className="nodrag nopan arc-btn"
          style={{ flex: 1, padding: '4px 8px', cursor: 'pointer' }}
          onClick={(e) => {
            e.stopPropagation();
            onNavigateToSource?.(arcNode);
          }}
        >
          Open Code
        </button>
        {canExtractPort && (
          <button
            type="button"
            className="nodrag nopan arc-btn"
            style={{ flex: 1, padding: '4px 8px', cursor: 'pointer' }}
            onClick={(e) => {
              e.stopPropagation();
              onExtractInterface?.(arcNode);
            }}
          >
            Extract Port
          </button>
        )}
      </div>

      <Handle
        type="source"
        position={Position.Bottom}
        style={{ background: isViolation ? '#ef4444' : '#71717a', width: 8, height: 8, border: 'none' }}
      />
    </div>
  );
};
