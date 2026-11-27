import React from 'react';
import { ArcNode } from '../../types/bridge';
import { MONO_FONT } from './constants';
import { OpenFileTarget, parseFileReference } from './fileReferences';
import { FileChip } from './FileChip';

function isSafeExternalHttpUrl(rawHref: string): boolean {
  try {
    const parsed = new URL(rawHref);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

export function renderPlainTextWithAutoFileChips(
  text: string,
  nodes: ArcNode[],
  onOpenFile: ((target: OpenFileTarget) => void) | undefined,
  keyPrefix: string
): React.ReactNode[] {
  if (!text) return [];
  const bareFileRegex =
    /(?:^|(?<=[\s([{"']))((?:file:\/\/)?\/?(?:[A-Za-z0-9_.-]+\/)*[A-Za-z0-9_.-]+\.(?:kt|kts|java|ts|tsx|js|jsx|mjs|py|mmd|json|yaml|yml|toml|xml|gradle|sql|md)(?:#L\d+(?:-L?\d+)?|:\d+(?::\d+)?)?)(?=$|[\s)\]}"'.,;!?])/g;
  const out: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = bareFileRegex.exec(text)) !== null) {
    const matchedStr = match[1];
    const startIdx = match.index;
    const ref = parseFileReference(matchedStr, nodes);
    if (!ref) continue;

    if (startIdx > lastIndex) {
      out.push(text.slice(lastIndex, startIdx));
    }
    out.push(
      <FileChip
        key={`${keyPrefix}-barefile-${startIdx}`}
        reference={ref}
        onOpenFile={onOpenFile}
      />
    );
    lastIndex = startIdx + matchedStr.length;
  }

  if (lastIndex < text.length) {
    out.push(text.slice(lastIndex));
  }
  return out;
}

/**
 * Renders inline Markdown tokens (`[label](url)`, `` `code` ``, `**bold**`, `*italic*`, `~~strike~~`,
 * plus automatic `<FileChip />` promotion for file references).
 */
export function renderInlineMarkdown(
  text: string,
  nodes: ArcNode[] = [],
  onOpenFile?: (target: OpenFileTarget) => void,
  keyPrefix: string = 'in'
): React.ReactNode[] {
  const tokenRegex =
    /(\[([^\]]+)\]\(([^)]+)\)|`([^`]+)`|\*\*([^*]+)\*\*|__([^_]+)__|~~([^~]+)~~|\*([^*\n]+)\*|(?<![A-Za-z0-9_])_([^_\n]+?)_(?![A-Za-z0-9_]))/g;

  const elements: React.ReactNode[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = tokenRegex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      const plainSlice = text.slice(lastIndex, match.index);
      elements.push(
        ...renderPlainTextWithAutoFileChips(
          plainSlice,
          nodes,
          onOpenFile,
          `${keyPrefix}-p-${lastIndex}`
        )
      );
    }

    const tokenKey = `${keyPrefix}-t-${match.index}`;

    if (match[2] !== undefined && match[3] !== undefined) {
      const label = match[2];
      const href = match[3].trim();
      const fileRef =
        parseFileReference(href, nodes, label) || parseFileReference(label, nodes);

      if (fileRef) {
        elements.push(
          <FileChip key={tokenKey} reference={fileRef} onOpenFile={onOpenFile} />
        );
      } else if (isSafeExternalHttpUrl(href)) {
        elements.push(
          <a
            key={tokenKey}
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            style={{
              color: '#60a5fa',
              textDecoration: 'underline',
              textUnderlineOffset: 2,
              cursor: 'pointer'
            }}
          >
            {label}
          </a>
        );
      } else {
        elements.push(<span key={tokenKey}>{label}</span>);
      }
    } else if (match[4] !== undefined) {
      const codeContent = match[4];
      const fileRef = parseFileReference(codeContent, nodes);
      if (fileRef) {
        elements.push(
          <FileChip key={tokenKey} reference={fileRef} onOpenFile={onOpenFile} />
        );
      } else {
        elements.push(
          <code
            key={tokenKey}
            style={{
              fontFamily: MONO_FONT,
              fontSize: 11.5,
              padding: '1px 5px',
              borderRadius: 4,
              background: '#1e1e24',
              border: '1px solid #3f3f46',
              color: '#e4e4e7'
            }}
          >
            {codeContent}
          </code>
        );
      }
    } else if (match[5] !== undefined || match[6] !== undefined) {
      const boldContent = match[5] ?? match[6];
      elements.push(
        <strong key={tokenKey} style={{ color: '#fafafa', fontWeight: 600 }}>
          {renderInlineMarkdown(boldContent, nodes, onOpenFile, `${tokenKey}-b`)}
        </strong>
      );
    } else if (match[7] !== undefined) {
      elements.push(
        <del key={tokenKey} style={{ color: '#94949e' }}>
          {renderInlineMarkdown(match[7], nodes, onOpenFile, `${tokenKey}-s`)}
        </del>
      );
    } else if (match[8] !== undefined || match[9] !== undefined) {
      const italicContent = match[8] ?? match[9];
      elements.push(
        <em key={tokenKey} style={{ color: '#d4d4d8', fontStyle: 'italic' }}>
          {renderInlineMarkdown(italicContent, nodes, onOpenFile, `${tokenKey}-i`)}
        </em>
      );
    }

    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < text.length) {
    elements.push(
      ...renderPlainTextWithAutoFileChips(
        text.slice(lastIndex),
        nodes,
        onOpenFile,
        `${keyPrefix}-end`
      )
    );
  }

  return elements;
}
