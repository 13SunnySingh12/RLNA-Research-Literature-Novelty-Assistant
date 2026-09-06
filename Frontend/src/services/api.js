import axios from 'axios';

import { clearAccessToken, getAccessToken } from './authClient';

/**
 * The single API client.
 *
 * Every request carries a fresh access token, and every error is normalized to
 * the shape the UI renders, so no component has to know what an axios error
 * looks like.
 */
export const api = axios.create({
  baseURL: import.meta.env.DEV ? '' : import.meta.env.VITE_API_BASE_URL || '',
  timeout: 120_000,
});

api.interceptors.request.use(async (config) => {
  const token = await getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const { config, response } = error;

    // A single retry with a freshly minted token covers the case where the
    // cached one expired between being read and being used.
    if (response?.status === 401 && config && !config.__retried) {
      config.__retried = true;
      clearAccessToken();
      const token = await getAccessToken({ force: true });
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
        return api(config);
      }
    }
    return Promise.reject(toApiError(error));
  },
);

export class ApiError extends Error {
  constructor({ code, message, status, traceId, fields }) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.traceId = traceId;
    this.fields = fields;
  }

  /** True when retrying the same request could plausibly succeed. */
  get retryable() {
    return this.status === 0 || this.status >= 500 || this.status === 429;
  }
}

function toApiError(error) {
  if (error instanceof ApiError) return error;

  const status = error.response?.status ?? 0;
  const envelope = error.response?.data?.error;

  if (envelope) {
    return new ApiError({
      code: envelope.code,
      message: envelope.message,
      status,
      traceId: envelope.traceId,
      fields: envelope.fields,
    });
  }
  if (error.code === 'ECONNABORTED') {
    return new ApiError({
      code: 'TIMEOUT',
      message: 'That took too long. It may still be running; refresh in a moment.',
      status: 0,
    });
  }
  if (status === 0) {
    return new ApiError({
      code: 'OFFLINE',
      // Free instances sleep, so a first request after idle legitimately fails.
      message: 'We could not reach the server. It may be waking up; try again shortly.',
      status: 0,
    });
  }
  return new ApiError({
    code: 'UNEXPECTED',
    message: 'Something went wrong. Please try again.',
    status,
  });
}

const unwrap = (promise) => promise.then((response) => response.data);

// --------------------------------------------------------------------------

export const authApi = {
  session: () => unwrap(api.get('/api/auth/session')),
  logout: () => unwrap(api.post('/api/auth/logout')),
};

export const projectsApi = {
  list: () => unwrap(api.get('/api/projects')),
  get: (id) => unwrap(api.get(`/api/projects/${id}`)),
  create: (body) => unwrap(api.post('/api/projects', body)),
  update: (id, body) => unwrap(api.patch(`/api/projects/${id}`, body)),
  remove: (id) => unwrap(api.delete(`/api/projects/${id}`)),
  analytics: (id) => unwrap(api.get(`/api/projects/${id}/analytics`)),
};

export const papersApi = {
  list: (params) => unwrap(api.get('/api/papers', { params })),
  get: (id) => unwrap(api.get(`/api/papers/${id}`)),
  update: (id, body) => unwrap(api.patch(`/api/papers/${id}`, body)),
  remove: (id) => unwrap(api.delete(`/api/papers/${id}`)),
  status: (id) => unwrap(api.get(`/api/papers/${id}/status`)),
  fileUrl: (id) => unwrap(api.get(`/api/papers/${id}/file`)),
  duplicate: (id) =>
    api.get(`/api/papers/${id}/duplicate`).then((r) => (r.status === 204 ? null : r.data)),
  reprocess: (id) => unwrap(api.post(`/api/papers/${id}/reprocess`)),
  tags: () => unwrap(api.get('/api/papers/tags')),

  upload: (files, projectId, onProgress) => {
    const form = new FormData();
    files.forEach((file) => form.append('files', file));
    return unwrap(
      api.post('/api/papers', form, {
        params: projectId ? { projectId } : undefined,
        onUploadProgress: (event) => {
          if (onProgress && event.total) {
            onProgress(Math.round((event.loaded / event.total) * 100));
          }
        },
      }),
    );
  },

  attachFile: (id, file) => {
    const form = new FormData();
    form.append('file', file);
    return unwrap(api.post(`/api/papers/${id}/file`, form));
  },
};

export const searchApi = {
  semantic: (body) => unwrap(api.post('/api/search/semantic', body)),
  keyword: (params) => unwrap(api.get('/api/search/keyword', { params })),
};

export const analysisApi = {
  summary: (paperId, refresh = false) =>
    unwrap(api.post(`/api/papers/${paperId}/summary`, { refresh })),
  concepts: (paperId, refresh = false) =>
    unwrap(api.post(`/api/papers/${paperId}/concepts`, { refresh })),
  askPaper: (paperId, body) => unwrap(api.post(`/api/papers/${paperId}/question`, body)),
  askProject: (projectId, body) => unwrap(api.post(`/api/projects/${projectId}/question`, body)),
  compare: (body) => unwrap(api.post('/api/analysis/compare', body)),
  researchGap: (body) => unwrap(api.post('/api/analysis/research-gap', body)),
  novelty: (body) => unwrap(api.post('/api/analysis/novelty', body)),
  literatureReview: (body) => unwrap(api.post('/api/analysis/literature-review', body)),
  history: (params) => unwrap(api.get('/api/analysis/history', { params })),
  get: (id) => unwrap(api.get(`/api/analysis/${id}`)),
  remove: (id) => unwrap(api.delete(`/api/analysis/${id}`)),
};

export const academicApi = {
  search: (params) => unwrap(api.get('/api/academic/search', { params })),
  import: (body) => unwrap(api.post('/api/academic/import', body)),
};

export const analyticsApi = {
  get: (projectId) => unwrap(api.get('/api/analytics', { params: { projectId } })),
};

export const exportApi = {
  download: async (analysisId, format) => {
    const response = await api.post(
      '/api/export',
      { analysisId, format },
      { responseType: 'blob' },
    );
    const disposition = response.headers['content-disposition'] || '';
    const match = /filename="?([^";]+)"?/.exec(disposition);
    return { blob: response.data, filename: match?.[1] || `rlna-export.${format}` };
  },
};
