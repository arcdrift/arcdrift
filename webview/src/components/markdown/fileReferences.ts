import { ArcNode } from '../../types/bridge';
import {
  FileTypeVisual,
  RECOGNIZED_FILE_EXTENSIONS,
  getFileTypeVisual
} from './fileTypeVisuals';

export interface FileReference {
  displayName: string;
  filePath: string;
  line?: number;
  endLine?: number;
  lineLabel?: string;
  nodeId?: string;
  visual: FileTypeVisual;
}

export interface OpenFileTarget {
  filePath: string;
  line?: number;
  nodeId?: string;
}

/**
 * Parses a potential file path, `file://` URL, or known `ArcNode` reference into a structured
 * `FileReference` suitable for rendering as an Antigravity-style interactive `<FileChip />`.
 */
export function parseFileReference(
  rawInput: string,
  nodes: ArcNode[] = [],
  customLabel?: string
): FileReference | null {
  const trimmed = rawInput.trim();
  if (!trimmed || trimmed.length > 260) return null;
  if (/^https?:\/\//i.test(trimmed)) return null;

  let working = trimmed.replace(/^file:\/\//i, '');
  let line: number | undefined;
  let endLine: number | undefined;
  let lineLabel: string | undefined;

  const hashMatch = working.match(/#L(\d+)(?:-L?(\d+))?$/i);
  if (hashMatch) {
    line = Number(hashMatch[1]);
    if (hashMatch[2]) endLine = Number(hashMatch[2]);
    lineLabel = endLine ? `#L${line}-${endLine}` : `#L${line}`;
    working = working.slice(0, hashMatch.index);
  } else {
    const colonMatch = working.match(/:(\d+)(?::\d+)?$/);
    if (colonMatch) {
      line = Number(colonMatch[1]);
      lineLabel = `:${line}`;
      working = working.slice(0, colonMatch.index);
    }
  }

  const normalized = working.replace(/\\/g, '/').trim();
  if (!normalized || /\s/.test(normalized)) return null;

  const baseName = normalized.split('/').pop() || normalized;
  const dotIdx = baseName.lastIndexOf('.');
  const ext = dotIdx > 0 ? baseName.slice(dotIdx + 1).toLowerCase() : '';

  if (ext && RECOGNIZED_FILE_EXTENSIONS.has(ext)) {
    const cleanRel = normalized.replace(/^\.?\//, '');
    const hasPathSegment = cleanRel.includes('/');
    const nonContainerNodes = nodes.filter(
      (n) => n.kind !== 'PACKAGE' && n.kind !== 'SYSTEM' && n.kind !== 'MODULE'
    );
    const matchedNode =
      nonContainerNodes.find((n) => {
        const sp = n.source?.filePath?.replace(/\\/g, '/');
        if (!sp) return false;
        return sp === normalized || sp === cleanRel || sp.endsWith(`/${cleanRel}`);
      }) ??
      (!hasPathSegment
        ? nonContainerNodes.find((n) => {
            const sp = n.source?.filePath?.replace(/\\/g, '/');
            if (!sp) return n.label.toLowerCase() === baseName.toLowerCase();
            return sp.split('/').pop()?.toLowerCase() === baseName.toLowerCase();
          })
        : undefined);

    const isGenericArcSpecExt =
      normalized === '.arc.mmd' ||
      normalized === '*.arc.mmd' ||
      baseName === '.arc.mmd';
    const resolvedFilePath = isGenericArcSpecExt
      ? 'architecture.arc.mmd'
      : matchedNode?.source?.filePath || normalized;
    const defaultLabel = isGenericArcSpecExt ? 'architecture.arc.mmd' : baseName;
    const cleanLabel = customLabel
      ? customLabel.replace(/^`+|`+$/g, '').trim()
      : defaultLabel;

    return {
      displayName: cleanLabel || defaultLabel,
      filePath: resolvedFilePath,
      line: line ?? matchedNode?.source?.startLine,
      endLine,
      lineLabel,
      nodeId: matchedNode?.id,
      visual: getFileTypeVisual(resolvedFilePath)
    };
  }

  if (nodes.length > 0 && /^[A-Za-z_][A-Za-z0-9_.]*$/.test(normalized)) {
    const matchedNode = nodes.find(
      (n) =>
        n.kind !== 'PACKAGE' &&
        n.kind !== 'SYSTEM' &&
        n.kind !== 'MODULE' &&
        (n.label === normalized || n.id === normalized)
    );
    if (matchedNode) {
      const filePath =
        matchedNode.source?.filePath ||
        (matchedNode.kind === 'TS_CLIENT'
          ? matchedNode.label
          : `${matchedNode.id.replace(/\./g, '/')}.kt`);
      return {
        displayName: customLabel
          ? customLabel.replace(/^`+|`+$/g, '').trim()
          : matchedNode.label,
        filePath,
        line: line ?? matchedNode.source?.startLine,
        endLine,
        lineLabel,
        nodeId: matchedNode.id,
        visual: getFileTypeVisual(filePath)
      };
    }
  }

  return null;
}

/**
 * Extracts clickable file/component chips from a tool_call step's title or JSON arguments.
 */
export function extractToolCallFileReferences(
  title: string,
  detailJson: string,
  nodes: ArcNode[] = []
): FileReference[] {
  const results: FileReference[] = [];
  const seen = new Set<string>();

  const pushRef = (candidate: string | undefined) => {
    if (!candidate) return;
    const ref = parseFileReference(candidate, nodes);
    if (ref && !seen.has(ref.filePath)) {
      seen.add(ref.filePath);
      results.push(ref);
    }
  };

  const parenMatch = title.match(/\(([^)]+)\)/);
  if (parenMatch && parenMatch[1]) {
    pushRef(parenMatch[1]);
  }

  try {
    const parsed = JSON.parse(detailJson);
    if (parsed && typeof parsed === 'object') {
      pushRef(parsed.filePath);
      pushRef(parsed.path);
      if (parsed.command && typeof parsed.command === 'object') {
        pushRef(parsed.command.sourceClassId);
        pushRef(parsed.command.targetNodeId);
        pushRef(parsed.command.fqName);
        pushRef(parsed.command.targetTypeFqName);
      }
    }
  } catch {
    // Ignore non-JSON details
  }

  return results;
}
