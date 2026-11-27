import React from 'react';
import { ArcNode } from '../types/bridge';
import { OpenFileTarget } from './markdown/fileReferences';
import { renderMarkdownBlocks } from './markdown/blockMarkdown';

export type { FileCategory, FileTypeVisual } from './markdown/fileTypeVisuals';
export { getFileTypeVisual, FileTypeIcon } from './markdown/fileTypeVisuals';
export type { FileReference, OpenFileTarget } from './markdown/fileReferences';
export { parseFileReference, extractToolCallFileReferences } from './markdown/fileReferences';
export { FileChip } from './markdown/FileChip';
export { renderInlineMarkdown } from './markdown/inlineMarkdown';

export interface AgentMarkdownRendererProps {
  content: string;
  nodes?: ArcNode[];
  onOpenFile?: (target: OpenFileTarget) => void;
}

/**
 * Antigravity-style Markdown renderer for agentic model responses.
 * Supports collapsible `<think>...</think>` reasoning blocks, GitHub-flavored Markdown,
 * fenced code blocks with copy actions, and interactive file chips with file-type icons.
 */
export const AgentMarkdownRenderer: React.FC<AgentMarkdownRendererProps> = ({
  content,
  nodes = [],
  onOpenFile
}) => {
  const thinkRegex = /<think>([\s\S]*?)(?:<\/think>|$)/gi;
  const thoughts: string[] = [];
  let match: RegExpExecArray | null;
  while ((match = thinkRegex.exec(content)) !== null) {
    const t = match[1].trim();
    if (t) thoughts.push(t);
  }

  const mainContent = content.replace(/<think>[\s\S]*?(?:<\/think>|$)/gi, '').trim();
  const hasMainContent = mainContent.length > 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {thoughts.map((thought, idx) => (
        <details
          key={`thought-${idx}`}
          open={!hasMainContent}
          style={{
            borderRadius: 6,
            border: '1px solid #27272a',
            background: 'rgba(24, 24, 27, 0.7)',
            padding: '6px 10px'
          }}
        >
          <summary
            style={{
              cursor: 'pointer',
              userSelect: 'none',
              fontSize: 11.5,
              fontWeight: 500,
              color: '#a1a1aa',
              display: 'flex',
              alignItems: 'center',
              gap: 6
            }}
          >
            <span style={{ color: '#818cf8' }}>💭</span>
            <span>Thought process</span>
          </summary>
          <div
            style={{
              marginTop: 6,
              paddingLeft: 8,
              borderLeft: '2px solid #3f3f46',
              opacity: 0.9
            }}
          >
            {renderMarkdownBlocks(thought, nodes, onOpenFile, `think-${idx}`)}
          </div>
        </details>
      ))}

      {hasMainContent && (
        <div>{renderMarkdownBlocks(mainContent, nodes, onOpenFile, 'main')}</div>
      )}
    </div>
  );
};
