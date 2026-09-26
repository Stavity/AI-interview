import { request } from './request';

export interface QuestionTagNode {
  code: string;
  name: string;
  parentCode: string | null;
  nodeKind: string;
  description: string | null;
  assignable: boolean;
  aliases: string[];
}

export interface QuestionTagSearchResponse {
  taxonomyVersion: number;
  query: string;
  results: Array<{
    node: QuestionTagNode;
    matchedBy: 'CODE' | 'NAME' | 'ALIAS' | 'DESCRIPTION';
  }>;
}

export const questionTagApi = {
  async search(query: string, limit = 20): Promise<QuestionTagSearchResponse> {
    const params = new URLSearchParams({ q: query, assignableOnly: 'true', limit: String(limit) });
    return request.get<QuestionTagSearchResponse>(`/api/question-tags/search?${params.toString()}`);
  },
};
