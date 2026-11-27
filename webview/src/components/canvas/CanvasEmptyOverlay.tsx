import React from 'react';
import { pluralize } from '../../bridge/ideBridge';

export interface CanvasEmptyOverlayProps {
  onlyDiffAndBlast: boolean;
  totalNodes: number;
  onResetFilters: () => void;
}

export const CanvasEmptyOverlay: React.FC<CanvasEmptyOverlayProps> = ({
  onlyDiffAndBlast,
  totalNodes,
  onResetFilters
}) => (
  <div
    style={{
      position: 'absolute',
      inset: 0,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      pointerEvents: 'none',
      zIndex: 10
    }}
  >
    <div
      style={{
        background: '#18181b',
        border: '1px solid #3f3f46',
        borderRadius: 10,
        padding: '20px 28px',
        color: '#f4f4f5',
        textAlign: 'center',
        pointerEvents: 'auto'
      }}
    >
      <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>
        {onlyDiffAndBlast
          ? 'No Diff or Boundary Violations in Current View'
          : 'No Nodes Match Current Filter'}
      </div>
      <div style={{ fontSize: 12, color: '#a1a1aa', marginBottom: 14 }}>
        {onlyDiffAndBlast
          ? `All ${pluralize(totalNodes, 'component')} ${totalNodes === 1 ? 'is' : 'are'} currently clean (UNCHANGED).`
          : 'Adjust C4 semantic zoom level or clear the search filter.'}
      </div>
      <button type="button" onClick={onResetFilters} className="arc-btn arc-btn-primary">
        Show All {pluralize(totalNodes, 'Node')}
      </button>
    </div>
  </div>
);
