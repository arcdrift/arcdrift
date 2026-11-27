import React from 'react';
import { ArcNode } from '../../types/bridge';
import { MONO_FONT } from './constants';
import { OpenFileTarget } from './fileReferences';
import { renderInlineMarkdown } from './inlineMarkdown';
import { MarkdownCodeBlock } from './MarkdownCodeBlock';

const ALERT_STYLES: Record<
  string,
  { border: string; bg: string; titleColor: string; label: string }
> = {
  NOTE: {
    border: '#3b82f6',
    bg: 'rgba(59, 130, 246, 0.1)',
    titleColor: '#93c5fd',
    label: 'Note'
  },
  TIP: {
    border: '#10b981',
    bg: 'rgba(16, 185, 129, 0.1)',
    titleColor: '#6ee7b7',
    label: 'Tip'
  },
  IMPORTANT: {
    border: '#a855f7',
    bg: 'rgba(168, 85, 247, 0.1)',
    titleColor: '#d8b4fe',
    label: 'Important'
  },
  WARNING: {
    border: '#f59e0b',
    bg: 'rgba(245, 158, 11, 0.1)',
    titleColor: '#fcd34d',
    label: 'Warning'
  },
  CAUTION: {
    border: '#ef4444',
    bg: 'rgba(239, 68, 68, 0.1)',
    titleColor: '#fca5a5',
    label: 'Caution'
  }
};

function dedentChildLines(lines: string[]): string[] {
  let minIndent = Infinity;
  for (const line of lines) {
    if (line.trim().length === 0) continue;
    const match = line.match(/^(\s*)/);
    const indent = match ? match[1].length : 0;
    if (indent < minIndent) minIndent = indent;
  }
  if (!Number.isFinite(minIndent) || minIndent <= 0) return lines;
  return lines.map((line) => (line.trim().length === 0 ? '' : line.slice(minIndent)));
}

export function renderMarkdownBlocks(
  markdown: string,
  nodes: ArcNode[],
  onOpenFile?: (target: OpenFileTarget) => void,
  blockKeyPrefix: string = 'blk'
): React.ReactNode[] {
  const normalized = markdown
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .replace(/^(?:[ \t]*\n)+/, '')
    .replace(/(?:\n[ \t]*)+$/, '');
  if (!normalized.trim()) return [];

  const lines = normalized.split('\n');
  const blocks: React.ReactNode[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();

    if (!trimmed) {
      i++;
      continue;
    }

    // 1. Fenced code block: ```lang ... ``` or ~~~lang ... ~~~
    const fenceMatch = trimmed.match(/^(`{3,}|~{3,})(.*)$/);
    if (fenceMatch) {
      const fenceSeq = fenceMatch[1];
      const fenceChar = fenceSeq[0];
      const fenceLen = fenceSeq.length;
      const fenceLang = fenceMatch[2].trim();
      const codeLines: string[] = [];
      i++;
      while (i < lines.length) {
        const closeMatch = lines[i].trim().match(/^(`{3,}|~{3,})\s*$/);
        if (closeMatch && closeMatch[1][0] === fenceChar && closeMatch[1].length >= fenceLen) {
          i++;
          break;
        }
        codeLines.push(lines[i]);
        i++;
      }
      blocks.push(
        <MarkdownCodeBlock
          key={`${blockKeyPrefix}-code-${i}`}
          language={fenceLang}
          code={codeLines.join('\n')}
          nodes={nodes}
          onOpenFile={onOpenFile}
        />
      );
      continue;
    }

    // 2. Horizontal Rule: --- or ***
    if (/^(?:---|\*\*\*|___)\s*$/.test(trimmed)) {
      blocks.push(
        <hr
          key={`${blockKeyPrefix}-hr-${i}`}
          style={{
            border: 'none',
            borderTop: '1px solid #27272a',
            margin: '10px 0'
          }}
        />
      );
      i++;
      continue;
    }

    // 3. Heading: # .. ######
    const headingMatch = trimmed.match(/^(#{1,6})\s+(.+)$/);
    if (headingMatch) {
      const level = headingMatch[1].length;
      const content = headingMatch[2];
      const fontSize = level === 1 ? 15 : level === 2 ? 13.5 : 12.5;
      blocks.push(
        <div
          key={`${blockKeyPrefix}-h-${i}`}
          style={{
            fontSize,
            fontWeight: 600,
            color: '#fafafa',
            marginTop: blocks.length > 0 ? 10 : 2,
            marginBottom: 4,
            paddingBottom: level <= 2 ? 4 : 0,
            borderBottom: level <= 2 ? '1px solid #27272a' : 'none',
            lineHeight: 1.4
          }}
        >
          {renderInlineMarkdown(content, nodes, onOpenFile, `${blockKeyPrefix}-h-${i}`)}
        </div>
      );
      i++;
      continue;
    }

    // 4. Blockquote / GitHub Alert: > ...
    if (trimmed.startsWith('>')) {
      const quoteLines: string[] = [];
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        quoteLines.push(lines[i].trim().replace(/^>\s?/, ''));
        i++;
      }
      const firstLine = quoteLines[0] || '';
      const alertMatch = firstLine.match(/^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*(.*)$/i);
      if (alertMatch) {
        const alertKey = alertMatch[1].toUpperCase();
        const style = ALERT_STYLES[alertKey] || ALERT_STYLES.NOTE;
        const bodyLines = [alertMatch[2], ...quoteLines.slice(1)].filter(Boolean);
        blocks.push(
          <div
            key={`${blockKeyPrefix}-alert-${i}`}
            style={{
              margin: '6px 0',
              padding: '8px 11px',
              borderRadius: 6,
              borderLeft: `3px solid ${style.border}`,
              background: style.bg,
              fontSize: 12,
              lineHeight: 1.55
            }}
          >
            <div
              style={{
                fontWeight: 600,
                color: style.titleColor,
                marginBottom: bodyLines.length > 0 ? 3 : 0,
                fontSize: 11.5
              }}
            >
              {style.label}
            </div>
            {bodyLines.length > 0 && (
              <div style={{ color: '#e4e4e7' }}>
                {renderInlineMarkdown(
                  bodyLines.join(' '),
                  nodes,
                  onOpenFile,
                  `${blockKeyPrefix}-alertb-${i}`
                )}
              </div>
            )}
          </div>
        );
      } else {
        blocks.push(
          <blockquote
            key={`${blockKeyPrefix}-quote-${i}`}
            style={{
              margin: '6px 0',
              padding: '4px 10px',
              borderLeft: '3px solid #52525b',
              background: 'rgba(39, 39, 42, 0.35)',
              borderRadius: '0 5px 5px 0',
              color: '#d4d4d8',
              fontSize: 12,
              lineHeight: 1.55
            }}
          >
            {renderInlineMarkdown(
              quoteLines.join(' '),
              nodes,
              onOpenFile,
              `${blockKeyPrefix}-q-${i}`
            )}
          </blockquote>
        );
      }
      continue;
    }

    // 5. Markdown Table: | col1 | col2 | followed by | --- | --- |
    if (
      trimmed.startsWith('|') &&
      i + 1 < lines.length &&
      /^\s*\|[\s:-]+\|/.test(lines[i + 1])
    ) {
      const parseRow = (rowLine: string) =>
        rowLine
          .trim()
          .replace(/^\|/, '')
          .replace(/(?<!\\)\|$/, '')
          .split(/(?<!\\)\|/)
          .map((c) => c.trim().replace(/\\\|/g, '|'));

      const headers = parseRow(lines[i]);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        rows.push(parseRow(lines[i]));
        i++;
      }

      blocks.push(
        <div
          key={`${blockKeyPrefix}-tbl-${i}`}
          style={{ margin: '8px 0', overflowX: 'auto' }}
        >
          <table
            style={{
              width: '100%',
              borderCollapse: 'collapse',
              fontSize: 11.5,
              border: '1px solid #27272a'
            }}
          >
            <thead>
              <tr style={{ background: '#18181b' }}>
                {headers.map((h, hIdx) => (
                  <th
                    key={hIdx}
                    style={{
                      textAlign: 'left',
                      padding: '5px 8px',
                      borderBottom: '1px solid #3f3f46',
                      color: '#fafafa',
                      fontWeight: 600
                    }}
                  >
                    {renderInlineMarkdown(
                      h,
                      nodes,
                      onOpenFile,
                      `${blockKeyPrefix}-th-${i}-${hIdx}`
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r, rIdx) => (
                <tr
                  key={rIdx}
                  style={{
                    background: rIdx % 2 === 0 ? '#09090b' : '#121215',
                    borderBottom: '1px solid #27272a'
                  }}
                >
                  {r.map((cell, cIdx) => (
                    <td
                      key={cIdx}
                      style={{
                        padding: '5px 8px',
                        color: '#d4d4d8',
                        verticalAlign: 'top'
                      }}
                    >
                      {renderInlineMarkdown(
                        cell,
                        nodes,
                        onOpenFile,
                        `${blockKeyPrefix}-td-${i}-${rIdx}-${cIdx}`
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
      continue;
    }

    // 6. Unordered or Ordered Lists (with nested lists & indented code/continuations)
    const listLineMatch = line.match(/^(\s*)([-*+]|\d+\.)\s+(.*)$/);
    if (listLineMatch) {
      const baseIndent = listLineMatch[1].length;
      const isOl = /^\d+\./.test(listLineMatch[2]);
      const itemRegex = isOl ? /^(\s*)(\d+\.)\s+(.*)$/ : /^(\s*)([-*+])\s+(.*)$/;
      const items: { text: string; checked?: boolean; childrenLines: string[] }[] = [];

      while (i < lines.length) {
        const cur = lines[i];
        const curTrimmed = cur.trim();
        if (!curTrimmed) {
          if (
            i + 1 < lines.length &&
            itemRegex.test(lines[i + 1]) &&
            (lines[i + 1].match(/^(\s*)/)?.[1].length ?? 0) === baseIndent
          ) {
            i++;
            continue;
          }
          break;
        }
        const curIndent = cur.match(/^(\s*)/)?.[1].length ?? 0;
        const m = cur.match(itemRegex);
        if (m && curIndent === baseIndent) {
          const rawItem = m[3];
          const taskMatch = rawItem.match(/^\[([ xX])\]\s+(.*)$/);
          items.push(
            taskMatch
              ? {
                  checked: taskMatch[1].toLowerCase() === 'x',
                  text: taskMatch[2],
                  childrenLines: []
                }
              : { text: rawItem, childrenLines: [] }
          );
          i++;
        } else if (curIndent > baseIndent && items.length > 0) {
          items[items.length - 1].childrenLines.push(cur);
          i++;
        } else {
          break;
        }
      }

      const ListTag = isOl ? 'ol' : 'ul';
      blocks.push(
        <ListTag
          key={`${blockKeyPrefix}-list-${i}`}
          style={{
            margin: '4px 0',
            paddingLeft: 18,
            display: 'flex',
            flexDirection: 'column',
            gap: 3,
            color: '#e4e4e7',
            fontSize: 12,
            lineHeight: 1.55
          }}
        >
          {items.map((item, idx) => (
            <li
              key={idx}
              style={{
                listStyleType: item.checked !== undefined ? 'none' : undefined,
                marginLeft: item.checked !== undefined ? -14 : 0
              }}
            >
              {item.checked !== undefined && (
                <span
                  style={{
                    display: 'inline-block',
                    marginRight: 6,
                    color: item.checked ? '#10b981' : '#94949e',
                    fontFamily: MONO_FONT
                  }}
                >
                  {item.checked ? '☑' : '☐'}
                </span>
              )}
              {renderInlineMarkdown(
                item.text,
                nodes,
                onOpenFile,
                `${blockKeyPrefix}-li-${i}-${idx}`
              )}
              {item.childrenLines.length > 0 && (
                <div style={{ marginTop: 3 }}>
                  {renderMarkdownBlocks(
                    dedentChildLines(item.childrenLines).join('\n'),
                    nodes,
                    onOpenFile,
                    `${blockKeyPrefix}-li-sub-${i}-${idx}`
                  )}
                </div>
              )}
            </li>
          ))}
        </ListTag>
      );
      continue;
    }

    // 7. Standard Paragraph (collect consecutive non-special lines)
    const paraLines: string[] = [];
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !/^(`{3,}|~{3,})/.test(lines[i].trim()) &&
      !/^#{1,6}\s+.+$/.test(lines[i].trim()) &&
      !lines[i].trim().startsWith('>') &&
      !/^(?:---|\*\*\*|___)\s*$/.test(lines[i].trim()) &&
      !/^[-*+]\s+/.test(lines[i].trim()) &&
      !/^\d+\.\s+/.test(lines[i].trim()) &&
      !(
        lines[i].trim().startsWith('|') &&
        i + 1 < lines.length &&
        /^\s*\|[\s:-]+\|/.test(lines[i + 1])
      )
    ) {
      paraLines.push(lines[i].trim());
      i++;
    }
    if (paraLines.length === 0 && i < lines.length) {
      paraLines.push(lines[i].trim());
      i++;
    }

    blocks.push(
      <p
        key={`${blockKeyPrefix}-p-${i}`}
        style={{
          margin: '4px 0',
          color: '#e4e4e7',
          fontSize: 12,
          lineHeight: 1.6,
          wordBreak: 'break-word'
        }}
      >
        {renderInlineMarkdown(
          paraLines.join(' '),
          nodes,
          onOpenFile,
          `${blockKeyPrefix}-p-${i}`
        )}
      </p>
    );
  }

  return blocks;
}
