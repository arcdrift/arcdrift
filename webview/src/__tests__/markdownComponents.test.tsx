import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ArcNode } from '../types/bridge';
import { getFileTypeVisual, FileTypeIcon } from '../components/markdown/fileTypeVisuals';
import {
  parseFileReference,
  extractToolCallFileReferences
} from '../components/markdown/fileReferences';
import { FileChip } from '../components/markdown/FileChip';
import { MarkdownCodeBlock } from '../components/markdown/MarkdownCodeBlock';
import { renderInlineMarkdown } from '../components/markdown/inlineMarkdown';
import { renderMarkdownBlocks } from '../components/markdown/blockMarkdown';

const sampleNodes: ArcNode[] = [
  {
    id: 'com.acme.OrderController',
    label: 'OrderController',
    c4Level: 'L3_COMPONENT',
    kind: 'REST_CONTROLLER',
    stereotypes: [],
    members: [],
    contracts: [],
    diffStatus: 'UNCHANGED',
    source: {
      filePath: 'src/main/kotlin/com/acme/OrderController.kt',
      startLine: 14,
      endLine: 80,
      language: 'kotlin'
    }
  },
  {
    id: 'ts.web.ordersClient',
    label: 'ordersClient',
    c4Level: 'L3_COMPONENT',
    kind: 'TS_CLIENT',
    stereotypes: [],
    members: [],
    contracts: [],
    diffStatus: 'UNCHANGED',
    source: {
      filePath: 'web/src/ordersClient.ts',
      startLine: 3,
      endLine: 25,
      language: 'typescript'
    }
  },
  {
    id: 'com.acme.pkg',
    label: 'pkg',
    c4Level: 'L3_COMPONENT',
    kind: 'PACKAGE',
    stereotypes: [],
    members: [],
    contracts: [],
    diffStatus: 'UNCHANGED'
  }
];

describe('markdown/fileTypeVisuals', () => {
  it('maps recognized extensions and line/URI suffixes to expected categories and badges', () => {
    expect(getFileTypeVisual('file:///src/App.kt#L12')).toMatchObject({
      category: 'kotlin',
      badge: 'KT'
    });
    expect(getFileTypeVisual('src/Main.java:10:2')).toMatchObject({
      category: 'java',
      badge: 'J'
    });
    expect(getFileTypeVisual('App.tsx')).toMatchObject({
      category: 'typescript',
      badge: 'TSX'
    });
    expect(getFileTypeVisual('client.ts')).toMatchObject({
      category: 'typescript',
      badge: 'TS'
    });
    expect(getFileTypeVisual('architecture.arc.mmd')).toMatchObject({
      category: 'mermaid',
      badge: 'ARC'
    });
    expect(getFileTypeVisual('unknown.bin')).toMatchObject({
      category: 'file',
      badge: 'FILE'
    });

    const iconHtml = renderToStaticMarkup(
      <div>
        <FileTypeIcon visual={getFileTypeVisual('OrderController.kt')} size={14} />
        <FileTypeIcon visual={getFileTypeVisual('OrderService.java')} />
        <FileTypeIcon visual={getFileTypeVisual('client.ts')} />
        <FileTypeIcon visual={getFileTypeVisual('legacy.js')} />
        <FileTypeIcon visual={getFileTypeVisual('worker.py')} />
        <FileTypeIcon visual={getFileTypeVisual('architecture.arc.mmd')} />
        <FileTypeIcon visual={getFileTypeVisual('package.json')} />
        <FileTypeIcon visual={getFileTypeVisual('unknown.bin')} />
      </div>
    );
    expect(iconHtml).toContain('<svg');
    expect(iconHtml).toContain('width="14"');
  });
});

describe('markdown/fileReferences', () => {
  it('parses file:// URIs, #L ranges, :line:col, .arc.mmd aliases, and ArcNode labels/IDs', () => {
    const uriRef = parseFileReference('file://src/main/kotlin/com/acme/OrderController.kt#L20-L35', sampleNodes);
    expect(uriRef).toMatchObject({
      displayName: 'OrderController.kt',
      filePath: 'src/main/kotlin/com/acme/OrderController.kt',
      line: 20,
      endLine: 35,
      lineLabel: '#L20-35',
      nodeId: 'com.acme.OrderController'
    });

    const colonRef = parseFileReference('web/src/ordersClient.ts:9:4', sampleNodes);
    expect(colonRef).toMatchObject({
      displayName: 'ordersClient.ts',
      filePath: 'web/src/ordersClient.ts',
      line: 9,
      lineLabel: ':9',
      nodeId: 'ts.web.ordersClient'
    });

    const specRef = parseFileReference('*.arc.mmd', sampleNodes);
    expect(specRef).toMatchObject({
      displayName: 'architecture.arc.mmd',
      filePath: 'architecture.arc.mmd'
    });

    const nodeRef = parseFileReference('com.acme.OrderController', sampleNodes, '`OrderCtrl`');
    expect(nodeRef).toMatchObject({
      displayName: 'OrderCtrl',
      filePath: 'src/main/kotlin/com/acme/OrderController.kt',
      line: 14,
      nodeId: 'com.acme.OrderController'
    });

    expect(parseFileReference('https://example.com/OrderController.kt', sampleNodes)).toBeNull();
    expect(parseFileReference('pkg', sampleNodes)).toBeNull();
  });

  it('extracts deduplicated file references from tool call titles and JSON arguments', () => {
    const refs = extractToolCallFileReferences(
      'read_file (web/src/ordersClient.ts)',
      JSON.stringify({
        filePath: 'web/src/ordersClient.ts',
        command: {
          sourceClassId: 'com.acme.OrderController'
        }
      }),
      sampleNodes
    );
    expect(refs.map((r) => r.filePath)).toEqual([
      'web/src/ordersClient.ts',
      'src/main/kotlin/com/acme/OrderController.kt'
    ]);
  });
});

describe('markdown/FileChip & MarkdownCodeBlock', () => {
  it('renders FileChip with line label and MarkdownCodeBlock with file-linked header', () => {
    const ref = parseFileReference('OrderController.kt#L14', sampleNodes);
    expect(ref).not.toBeNull();
    if (!ref) return;

    const chipHtml = renderToStaticMarkup(<FileChip reference={ref} />);
    expect(chipHtml).toContain('OrderController.kt');
    expect(chipHtml).toContain('#L14');
    expect(chipHtml).toContain('Open src/main/kotlin/com/acme/OrderController.kt (line 14) in IDE editor');

    const codeHtml = renderToStaticMarkup(
      <MarkdownCodeBlock
        language="kotlin OrderController.kt"
        code="class OrderController"
        nodes={sampleNodes}
      />
    );
    expect(codeHtml).toContain('OrderController.kt');
    expect(codeHtml).toContain('class OrderController');
    expect(codeHtml).toContain('Copy');
  });
});

describe('markdown/inlineMarkdown & blockMarkdown', () => {
  it('promotes bare file paths and inline links to FileChips and renders GitHub alerts and tables', () => {
    const inlineHtml = renderToStaticMarkup(
      <span>
        {renderInlineMarkdown(
          'Inspect web/src/ordersClient.ts, `OrderController.kt`, `unknown`, [File](OrderController.kt), [Docs](https://example.com), [Bad](javascript:void(0)), **bold**, __bold2__, ~~strike~~, and *italic*',
          sampleNodes
        )}
      </span>
    );
    expect(inlineHtml).toContain('ordersClient.ts');
    expect(inlineHtml).toContain('OrderController.kt');
    expect(inlineHtml).toContain('https://example.com');
    expect(inlineHtml).not.toContain('javascript:');
    expect(inlineHtml).toContain('<strong');
    expect(inlineHtml).toContain('<del');
    expect(inlineHtml).toContain('<em');
    expect(inlineHtml).toContain('<code');

    const blockHtml = renderToStaticMarkup(
      <div>
        {renderMarkdownBlocks(
          [
            '# Heading 1',
            '## Heading 2',
            '### Heading 3',
            '---',
            '> Plain blockquote line',
            '',
            '> [!NOTE] Note alert',
            '',
            '> [!TIP] Tip alert',
            '',
            '> [!IMPORTANT] Important alert',
            '',
            '> [!WARNING] Check OrderController.kt#L14',
            '',
            '> [!CAUTION] Caution alert',
            '',
            '- [x] Completed task',
            '- [ ] Pending task',
            '',
            '| Col1 | Col2 |',
            '| --- | --- |',
            '| A | B |'
          ].join('\n'),
          sampleNodes
        )}
      </div>
    );
    expect(blockHtml).toContain('Heading 1');
    expect(blockHtml).toContain('Plain blockquote line');
    expect(blockHtml).toContain('Note');
    expect(blockHtml).toContain('Tip');
    expect(blockHtml).toContain('Important');
    expect(blockHtml).toContain('Warning');
    expect(blockHtml).toContain('Caution');
    expect(blockHtml).toContain('☑');
    expect(blockHtml).toContain('☐');
    expect(blockHtml).toContain('OrderController.kt');
    expect(blockHtml).toContain('<table');
    expect(blockHtml).toContain('Col1');
  });

  it('never hangs on non-heading hash lines (#42, #region, #######, bare #) and stops paragraphs before ~~~ fences', () => {
    const trickyMarkdown = [
      'Paragraph intro line before hash tokens',
      '#42 fixed controller boundary check',
      '#regionRepositorySection',
      '####### seven hashes is not an ATX heading',
      '#',
      'Paragraph line right before tilde fence',
      '~~~ts',
      'const endpoint = "/v1/orders";',
      '~~~',
      'Trailing paragraph after tilde fence'
    ].join('\n');

    const html = renderToStaticMarkup(
      <div>{renderMarkdownBlocks(trickyMarkdown, sampleNodes)}</div>
    );
    expect(html).toContain('#42 fixed controller boundary check');
    expect(html).toContain('#regionRepositorySection');
    expect(html).toContain('####### seven hashes is not an ATX heading');
    expect(html).toContain('const endpoint = &quot;/v1/orders&quot;;');
    expect(html).toContain('Trailing paragraph after tilde fence');
  });
});
