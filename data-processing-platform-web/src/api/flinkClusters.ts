import type { ApiResponse } from './auth';
import { http } from './http';

export type FlinkDeploymentMode = 'STANDALONE' | 'KUBERNETES_OPERATOR';

export interface FlinkClusterRecord {
  id: number;
  name: string;
  environment: 'DEV' | 'STAGING' | 'PROD';
  deploymentMode: FlinkDeploymentMode;
  restUrl?: string;
  sqlGatewayUrl?: string;
  kubeApiUrl?: string;
  kubeNamespace?: string;
  kubeTokenEnv?: string;
  flinkImage?: string;
  serviceAccount?: string;
  defaultForEnvironment: boolean;
  enabled: boolean;
  owner?: string;
}

const basePath = '/realtime/flink-clusters';
const unwrap = <T,>(response: { data: ApiResponse<T> }) => response.data.data;

export const listFlinkClusters = () => http.get<ApiResponse<FlinkClusterRecord[]>>(basePath).then(unwrap);
export const createFlinkCluster = (data: Partial<FlinkClusterRecord>) => http.post<ApiResponse<FlinkClusterRecord>>(basePath, data).then(unwrap);
export const updateFlinkCluster = (id: number, data: Partial<FlinkClusterRecord>) => http.put<ApiResponse<void>>(`${basePath}/${id}`, data).then(unwrap);
export const deleteFlinkCluster = (id: number) => http.delete<ApiResponse<void>>(`${basePath}/${id}`).then(unwrap);
