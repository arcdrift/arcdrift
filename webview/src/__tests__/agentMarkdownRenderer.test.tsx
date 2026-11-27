import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { AgentMarkdownRenderer } from '../components/AgentMarkdownRenderer';

describe('AgentMarkdownRenderer', () => {
  it('renders single-underscore italics without mangling snake_case identifiers', () => {
    const html = renderToStaticMarkup(
      <AgentMarkdownRenderer content="Use _careful_ naming for get_architecture_slice." />
    );
    expect(html).toContain('<em');
    expect(html).toContain('careful</em>');
    expect(html).toContain('get_architecture_slice');
  });

  it('renders nested lists and indented fenced code blocks inside list items', () => {
    const md = [
      '1. Top step',
      '   - Nested bullet',
      '   ```ts',
      '   const x = 1;',
      '   ```',
      '2. Second step'
    ].join('\n');
    const html = renderToStaticMarkup(<AgentMarkdownRenderer content={md} />);
    expect(html).toContain('<ol');
    expect(html).toContain('<ul');
    expect(html).toContain('Nested bullet');
    expect(html).toContain('const x = 1;');
  });

  it('preserves escaped pipes inside Markdown table cells', () => {
    const md = [
      '| Policy | Example |',
      '| :--- | :--- |',
      '| `FORBIDDEN` | `A \\| B` |'
    ].join('\n');
    const html = renderToStaticMarkup(<AgentMarkdownRenderer content={md} />);
    expect(html).toContain('<table');
    expect(html).toContain('A | B');
  });

  it('renders collapsible <think> blocks and tilde fenced code blocks', () => {
    const md = [
      '<think>Checking OrderController.kt</think>',
      '',
      '~~~kotlin',
      'val ok = true',
      '~~~'
    ].join('\n');
    const html = renderToStaticMarkup(<AgentMarkdownRenderer content={md} />);
    expect(html).toContain('Thought process');
    expect(html).toContain('val ok = true');
  });
});
