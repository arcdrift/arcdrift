export const DEFAULT_AGENT_BASE_URL = 'http://localhost:11434';
export const DEFAULT_AGENT_MAX_TURNS = 30;

export interface AgentConfig {
  baseUrl: string;
  model: string;
  apiKey: string;
  maxTurns: number;
}

export function sanitizeMaxTurns(value: number): number {
  return Math.max(1, Math.min(100, Number(value) || DEFAULT_AGENT_MAX_TURNS));
}

export function loadStoredAgentConfig(
  storage: Pick<Storage, 'getItem'> | undefined =
    typeof window !== 'undefined' ? window.localStorage : undefined
): AgentConfig {
  try {
    const savedTurns = Number(storage?.getItem('arcdrift.agentMaxTurns'));
    return {
      baseUrl: storage?.getItem('arcdrift.agentBaseUrl') || DEFAULT_AGENT_BASE_URL,
      model: storage?.getItem('arcdrift.agentModel') || '',
      apiKey: '',
      maxTurns:
        Number.isFinite(savedTurns) && savedTurns >= 1
          ? Math.min(100, savedTurns)
          : DEFAULT_AGENT_MAX_TURNS
    };
  } catch {
    return {
      baseUrl: DEFAULT_AGENT_BASE_URL,
      model: '',
      apiKey: '',
      maxTurns: DEFAULT_AGENT_MAX_TURNS
    };
  }
}

export function saveStoredAgentConfig(
  config: AgentConfig,
  storage: (Pick<Storage, 'setItem'> & Partial<Pick<Storage, 'removeItem'>>) | undefined =
    typeof window !== 'undefined' ? window.localStorage : undefined
): void {
  const sanitizedTurns = sanitizeMaxTurns(config.maxTurns);
  try {
    storage?.setItem('arcdrift.agentBaseUrl', config.baseUrl);
    storage?.setItem('arcdrift.agentModel', config.model);
    storage?.removeItem?.('arcdrift.agentApiKey');
    storage?.setItem('arcdrift.agentMaxTurns', String(sanitizedTurns));
  } catch {
    // Ignore storage errors
  }
}

export interface DebouncedConfigSaver {
  schedule: (config: AgentConfig, immediate?: boolean) => void;
  cancel: () => void;
}

export function createDebouncedConfigSaver(
  onSave: (sanitized: AgentConfig) => void,
  delayMs: number = 300
): DebouncedConfigSaver {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const cancel = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };
  const schedule = (config: AgentConfig, immediate: boolean = false) => {
    const sanitized: AgentConfig = {
      ...config,
      maxTurns: sanitizeMaxTurns(config.maxTurns)
    };
    saveStoredAgentConfig(sanitized);
    cancel();
    if (immediate) {
      onSave(sanitized);
    } else {
      timer = setTimeout(() => {
        timer = null;
        onSave(sanitized);
      }, delayMs);
    }
  };
  return { schedule, cancel };
}
