import React from 'react';
import { BaseEdge, EdgeLabelRenderer, EdgeProps, getSmoothStepPath } from '@xyflow/react';
import { ArcEdge } from '../types/bridge';

export interface ConstraintEdgeData extends Record<string, unknown> {
  arcEdge: ArcEdge;
}

const MONO_FONT =
  "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace";

export const ConstraintEdge: React.FC<EdgeProps> = ({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  data
}) => {
  const edgeData = data as ConstraintEdgeData | undefined;
  const arcEdge = edgeData?.arcEdge;

  const isMultiLayerSpan = Math.abs(targetY - sourceY) > 240;
  const bendCenterY = isMultiLayerSpan ? sourceY + 44 : undefined;

  const [edgePath, rawLabelX, rawLabelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    borderRadius: 8,
    offset: 24,
    centerY: bendCenterY
  });

  // If an edge jumps across an intermediate layer, route its horizontal jog at (sourceY + 44)
  // in the inter-layer gap and place the label along the vertical corridor at targetX.
  const labelX = isMultiLayerSpan ? targetX : rawLabelX;
  const labelY = isMultiLayerSpan ? targetY - 38 : rawLabelY;

  const isViolation =
    arcEdge?.diffStatus === 'VIOLATION' || arcEdge?.kind === 'FORBIDDEN_RULE';
  const isCycle = arcEdge?.inCycle === true;
  const isContract = arcEdge?.kind === 'HTTP_CONTRACT';

  const strokeColor = isViolation || isCycle
    ? '#ef4444'
    : isContract
      ? '#71717a'
      : '#52525b';

  const strokeDasharray =
    isViolation || isCycle
      ? '6 4'
      : isContract || arcEdge?.kind === 'IMPLEMENTS'
        ? '4 3'
        : undefined;

  const fullLabel =
    arcEdge?.label ||
    (isViolation ? 'Boundary Violation' : isCycle ? 'Cyclic Dependency' : undefined);

  // Keep violation pills compact on the edge itself (the full rule explanation is inside the node card)
  const compactLabel =
    isViolation && fullLabel && fullLabel.length > 28
      ? fullLabel.replace(/^(\[[^\]]+\])\s+.*$/, '$1 violation')
      : fullLabel;

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        style={{
          stroke: strokeColor,
          strokeWidth: isViolation || isCycle ? 2.2 : 1.6,
          strokeDasharray,
          cursor: 'pointer'
        }}
      />
      {compactLabel && (
        <EdgeLabelRenderer>
          <div
            title={fullLabel}
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              background: isViolation || isCycle ? '#2a1215' : '#18181b',
              color: isViolation || isCycle ? '#fca5a5' : '#d4d4d8',
              border: `1px solid ${isViolation || isCycle ? '#ef4444' : '#3f3f46'}`,
              borderRadius: 5,
              padding: '2px 8px',
              fontFamily: MONO_FONT,
              fontSize: 12,
              fontWeight: 500,
              pointerEvents: 'all',
              cursor: 'pointer',
              whiteSpace: 'nowrap',
              boxShadow: '0 4px 10px rgba(0, 0, 0, 0.65)',
              zIndex: 30
            }}
            className="nodrag nopan"
          >
            {isViolation && !compactLabel.startsWith('⛔')
              ? `⛔ ${compactLabel}`
              : isCycle && !compactLabel.startsWith('🔄')
                ? `🔄 ${compactLabel}`
                : compactLabel}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
};
