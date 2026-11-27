import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { renderMarkdownBlocks } from '../components/markdown/blockMarkdown';
import { filterGraphByZoomAndDiff } from '../bridge/ideBridge';
import { parseFileReference } from '../components/markdown/fileReferences';
import { C4NodeCard } from '../components/C4NodeCard';
import { createDebouncedConfigSaver } from '../components/canvas/agentConfigStorage';
import { reduceIncomingIdeMessage, CanvasIncomingState } from '../components/canvas/canvasActions';
import { ArcGraph, ArcNode } from '../types/bridge';

describe('Second-pass hardening regressions', () => {
  it('renders 4-space-indented nested list siblings as siblings inside one nested <ul> rather than staircase-nesting', () => {
    const md = [
      '- Parent item',
      '    - Child one',
      '    - Child two',
      '        - Grandchild'
    ].join('\n');

    const html = renderToStaticMarkup(<div>{renderMarkdownBlocks(md, [])}</div>);
    // Child one and Child two must be sibling <li> elements inside the SAME nested <ul>, not nested <ul> inside Child one
    expect(html).toContain('Parent item');
    expect(html).toContain('Child one</li><li');
    expect(html).toContain('Child two<div');
    expect(html).toContain('Grandchild');
  });

  it('rolls up L3 edges to their nearest L2 container instead of climbing all the way to L1_SYSTEM', () => {
    const graph: ArcGraph = {
      projectName: 'Hierarchy',
      nodes: [
        { id: 'sys', label: 'CoreSystem', c4Level: 'L1_SYSTEM', kind: 'SYSTEM', stereotypes: [], members: [], contracts: [], diffStatus: 'UNCHANGED' },
        { id: 'pkg.web', label: 'web', c4Level: 'L2_CONTAINER', kind: 'PACKAGE', parentId: 'sys', stereotypes: [], members: [], contracts: [], diffStatus: 'UNCHANGED' },
        { id: 'pkg.repo', label: 'repo', c4Level: 'L2_CONTAINER', kind: 'PACKAGE', parentId: 'sys', stereotypes: [], members: [], contracts: [], diffStatus: 'UNCHANGED' },
        { id: 'pkg.web.Ctrl', label: 'Ctrl', c4Level: 'L3_COMPONENT', kind: 'REST_CONTROLLER', parentId: 'pkg.web', stereotypes: [], members: [], contracts: [], diffStatus: 'UNCHANGED' },
        { id: 'pkg.repo.Repo', label: 'Repo', c4Level: 'L3_COMPONENT', kind: 'REPOSITORY', parentId: 'pkg.repo', stereotypes: [], members: [], contracts: [], diffStatus: 'UNCHANGED' }
      ],
      edges: [
        { id: 'e1', sourceId: 'pkg.web.Ctrl', targetId: 'pkg.repo.Repo', kind: 'DEPENDS_ON', weight: 1, inCycle: false, diffStatus: 'UNCHANGED' }
      ],
      rules: [],
      metadata: {}
    };

    const l2Filtered = filterGraphByZoomAndDiff(graph, 'L2_CONTAINER', false);
    expect(l2Filtered.edges).toHaveLength(1);
    expect(l2Filtered.edges[0].sourceId).toBe('pkg.web');
    expect(l2Filtered.edges[0].targetId).toBe('pkg.repo');

    const l1Filtered = filterGraphByZoomAndDiff(graph, 'L1_SYSTEM', false);
    expect(l1Filtered.edges).toHaveLength(0);
  });

  it('does not hijack multi-segment file references to a different node sharing only the basename', () => {
    const nodes: ArcNode[] = [
      {
        id: 'ts.frontend.index',
        label: 'index',
        c4Level: 'L3_COMPONENT',
        kind: 'TS_CLIENT',
        stereotypes: [],
        members: [],
        contracts: [],
        source: { filePath: 'frontend/src/index.ts', startLine: 1, endLine: 10, language: 'typescript' },
        diffStatus: 'UNCHANGED'
      }
    ];

    const multiSeg = parseFileReference('backend/src/index.ts', nodes);
    expect(multiSeg).not.toBeNull();
    expect(multiSeg?.filePath).toBe('backend/src/index.ts');
    expect(multiSeg?.nodeId).toBeUndefined();

    const bareName = parseFileReference('index.ts', nodes);
    expect(bareName?.filePath).toBe('frontend/src/index.ts');
    expect(bareName?.nodeId).toBe('ts.frontend.index');
  });

  it('stops double-click propagation on C4NodeCard so NAVIGATE_TO_SOURCE fires once', () => {
    const onNavigate = vi.fn();
    const stopPropagation = vi.fn();
    const node: ArcNode = {
      id: 'com.acme.OrderService',
      label: 'OrderService',
      c4Level: 'L3_COMPONENT',
      kind: 'SERVICE_BEAN',
      stereotypes: [],
      members: [],
      contracts: [],
      diffStatus: 'UNCHANGED'
    };

    const element = C4NodeCard({
      id: node.id,
      type: 'c4Card',
      data: { arcNode: node, onNavigateToSource: onNavigate },
      selected: false,
      selectable: true,
      deletable: true,
      draggable: true,
      dragging: false,
      zIndex: 0,
      isConnectable: false,
      positionAbsoluteX: 0,
      positionAbsoluteY: 0
    }) as React.ReactElement<{ onDoubleClick?: (e: { stopPropagation: () => void }) => void }>;

    element.props.onDoubleClick?.({ stopPropagation });
    expect(stopPropagation).toHaveBeenCalledTimes(1);
    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(onNavigate).toHaveBeenCalledWith(node);
  });

  it('preserves unsaved typed agentApiKey when AGENT_CONFIG_LOADED arrives with empty apiKey', () => {
    const prev: CanvasIncomingState = {
      graph: { projectName: 'Demo', nodes: [], edges: [], rules: [], metadata: {} },
      statusToast: 'Ready',
      agentBaseUrl: 'http://127.0.0.1:8080/v1',
      agentModel: 'old',
      agentModels: [],
      agentApiKey: 'sk-unsaved-secret',
      agentHasSavedApiKey: false,
      agentMaxTurns: 6,
      agentRunning: false,
      agentSteps: []
    };

    const reduced = reduceIncomingIdeMessage(prev, {
      type: 'AGENT_CONFIG_LOADED',
      baseUrl: 'http://127.0.0.1:11434/v1',
      model: 'qwen2.5-coder',
      apiKey: '',
      hasSavedApiKey: true,
      maxTurns: 10
    });

    expect(reduced.agentApiKey).toBe('sk-unsaved-secret');
    expect(reduced.agentHasSavedApiKey).toBe(true);
    expect(reduced.agentModel).toBe('qwen2.5-coder');
  });

  it('debounces rapid SAVE_AGENT_CONFIG calls, supports immediate flush, and cancels cleanly on unmount', () => {
    vi.useFakeTimers();
    try {
      const saved: string[] = [];
      const saver = createDebouncedConfigSaver((cfg) => saved.push(cfg.apiKey), 300);

      saver.schedule({ baseUrl: 'http://localhost:11434', model: 'm', apiKey: 's', maxTurns: 10 });
      saver.schedule({ baseUrl: 'http://localhost:11434', model: 'm', apiKey: 'sk', maxTurns: 10 });
      saver.schedule({ baseUrl: 'http://localhost:11434', model: 'm', apiKey: 'sk-final', maxTurns: 10 });
      expect(saved).toEqual([]);

      vi.advanceTimersByTime(300);
      expect(saved).toEqual(['sk-final']);

      // Immediate flush cancels pending timer and saves synchronously
      saver.schedule({ baseUrl: 'http://localhost:11434', model: 'm', apiKey: 'sk-pending', maxTurns: 10 });
      saver.schedule({ baseUrl: 'http://localhost:11434', model: 'm', apiKey: 'sk-now', maxTurns: 10 }, true);
      expect(saved).toEqual(['sk-final', 'sk-now']);
      vi.advanceTimersByTime(500);
      expect(saved).toEqual(['sk-final', 'sk-now']);

      // Cancel prevents timer from firing after unmount
      saver.schedule({ baseUrl: 'http://localhost:11434', model: 'm', apiKey: 'sk-cancelled', maxTurns: 10 });
      saver.cancel();
      vi.advanceTimersByTime(500);
      expect(saved).toEqual(['sk-final', 'sk-now']);
    } finally {
      vi.useRealTimers();
    }
  });
});
