import React from 'react';
import { C4Level } from '../types/bridge';
import { CanvasHealthMetrics, pluralize } from '../bridge/ideBridge';

export interface SpecToolbarProps {
  projectName: string;
  zoomLevel: C4Level;
  onlyDiffAndBlast: boolean;
  sandboxPreviewMode: boolean;
  snapToGrid?: boolean;
  searchQuery: string;
  metrics: CanvasHealthMetrics;
  onChangeZoomLevel: (level: C4Level) => void;
  onToggleDiffFilter: () => void;
  onToggleSandboxMode: () => void;
  onToggleSnapToGrid?: () => void;
  onSearchChange: (q: string) => void;
  onAddGhostNode: () => void;
  onAddForbiddenRule: () => void;
  onRelayout: () => void;
  onRefresh?: () => void;
  onHandoffToAgent: () => void;
}

export const SpecToolbar: React.FC<SpecToolbarProps> = ({
  projectName,
  zoomLevel,
  onlyDiffAndBlast,
  sandboxPreviewMode,
  snapToGrid = true,
  searchQuery,
  metrics,
  onChangeZoomLevel,
  onToggleDiffFilter,
  onToggleSandboxMode,
  onToggleSnapToGrid,
  onSearchChange,
  onAddGhostNode,
  onAddForbiddenRule,
  onRelayout,
  onRefresh,
  onHandoffToAgent
}) => {
  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 10,
        padding: '10px 14px',
        background: '#09090b',
        borderBottom: '1px solid #27272a',
        color: '#f4f4f5',
        fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        fontSize: 12
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <span style={{ fontWeight: 700, fontSize: 13, color: '#fafafa', letterSpacing: '0.02em' }}>
          ARCDRIFT
        </span>
        <span
          style={{
            background: '#18181b',
            color: '#a1a1aa',
            padding: '3px 8px',
            borderRadius: 5,
            border: '1px solid #27272a',
            fontWeight: 500
          }}
        >
          {projectName}
        </span>

        <select
          aria-label="C4 Semantic Zoom"
          value={zoomLevel}
          onChange={(e) => onChangeZoomLevel(e.target.value as C4Level)}
          style={{
            background: '#18181b',
            color: '#e4e4e7',
            border: '1px solid #3f3f46',
            borderRadius: 6,
            padding: '5px 9px',
            fontSize: 12,
            fontWeight: 500,
            cursor: 'pointer'
          }}
        >
          <option value="L2_CONTAINER">C4 L2: Containers & Packages</option>
          <option value="L3_COMPONENT">C4 L3: Components & Contracts</option>
          <option value="L4_CODE">C4 L4: Classes & Code</option>
        </select>

        <input
          type="text"
          placeholder="Filter component or @Stereotype..."
          value={searchQuery}
          onChange={(e) => onSearchChange(e.target.value)}
          style={{
            background: '#18181b',
            color: '#f4f4f5',
            border: '1px solid #3f3f46',
            borderRadius: 6,
            padding: '5px 10px',
            fontSize: 12,
            width: 195
          }}
        />

        <button
          type="button"
          onClick={onToggleDiffFilter}
          className={`arc-btn ${onlyDiffAndBlast ? 'arc-btn-active' : ''}`}
          title="Toggle between showing all components or only components with diffs/violations"
        >
          {onlyDiffAndBlast ? 'Filter: Diffs & Violations' : 'Filter: All Components'}
        </button>

        <button
          type="button"
          onClick={onToggleSandboxMode}
          className={`arc-btn ${sandboxPreviewMode ? 'arc-btn-active' : ''}`}
          title="Toggle between writing refactoring changes directly to disk/PSI or previewing in memory"
        >
          {sandboxPreviewMode ? 'Mode: Sandbox Preview' : 'Mode: Apply to Code'}
        </button>

        {onToggleSnapToGrid && (
          <button
            type="button"
            onClick={onToggleSnapToGrid}
            className={`arc-btn ${snapToGrid ? 'arc-btn-active' : ''}`}
            title="Toggle 20px grid snapping when dragging components"
          >
            {snapToGrid ? 'Grid Snap: On (20px)' : 'Grid Snap: Off'}
          </button>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <span
          role="button"
          tabIndex={0}
          onClick={onToggleDiffFilter}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              onToggleDiffFilter();
            }
          }}
          title="Click to toggle filter between all components and diffs/violations"
          style={{
            padding: '4px 9px',
            borderRadius: 6,
            cursor: 'pointer',
            userSelect: 'none',
            background:
              metrics.violationCount > 0 || metrics.cycleEdgeCount > 0
                ? 'rgba(127, 29, 29, 0.35)'
                : '#18181b',
            border: `1px solid ${
              metrics.violationCount > 0 || metrics.cycleEdgeCount > 0
                ? 'rgba(239, 68, 68, 0.55)'
                : '#27272a'
            }`,
            color:
              metrics.violationCount > 0 || metrics.cycleEdgeCount > 0 ? '#fca5a5' : '#a1a1aa',
            fontWeight: metrics.violationCount > 0 || metrics.cycleEdgeCount > 0 ? 600 : 500
          }}
        >
          {metrics.violationCount > 0
            ? `⛔ ${pluralize(metrics.violationCount, 'Violation')}`
            : '0 Violations'}
          {metrics.cycleEdgeCount > 0
            ? ` · ⟳ ${pluralize(metrics.cycleEdgeCount, 'Cycle Edge')}`
            : ''}{' '}
          · {pluralize(metrics.contractCount, 'Contract')}
        </span>

        <button type="button" onClick={onAddGhostNode} className="arc-btn">
          + Ghost Component
        </button>

        <button type="button" onClick={onAddForbiddenRule} className="arc-btn">
          + Boundary Rule
        </button>

        <button type="button" onClick={onRelayout} className="arc-btn">
          Auto-Layout
        </button>

        {onRefresh && (
          <button
            type="button"
            onClick={onRefresh}
            className="arc-btn"
            title="Rescan repository and reload architecture graph"
          >
            Refresh
          </button>
        )}

        <button type="button" onClick={onHandoffToAgent} className="arc-btn arc-btn-primary">
          Handoff to Agent
        </button>
      </div>
    </div>
  );
};
