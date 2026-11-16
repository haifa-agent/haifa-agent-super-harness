import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  completeLarkAuthorization,
  completeLarkConfiguration,
  installLarkIntegration,
  loadLarkIntegrationStatus,
  setLarkAppCredentials,
  startLarkAuthorization,
  startLarkConfiguration,
} from "./api";

export const larkIntegrationQueryKey = ["integrations", "lark"] as const;

export function useLarkIntegrationStatus(enabled = true) {
  return useQuery({
    queryKey: larkIntegrationQueryKey,
    queryFn: ({ signal }) => loadLarkIntegrationStatus(signal),
    enabled,
    staleTime: 15_000,
  });
}

export function useVerifyLarkIntegration() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => loadLarkIntegrationStatus(undefined, true),
    onSuccess: (status) => {
      queryClient.setQueryData(larkIntegrationQueryKey, status);
    },
  });
}

export function useInstallLarkIntegration() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: installLarkIntegration,
    onSuccess: async (result) => {
      queryClient.setQueryData(larkIntegrationQueryKey, result.status);
      await queryClient.invalidateQueries({
        queryKey: larkIntegrationQueryKey,
      });
      await queryClient.invalidateQueries({ queryKey: ["skills"] });
      await queryClient.invalidateQueries({ queryKey: ["capabilities"] });
    },
  });
}

export function useStartLarkAuthorization() {
  return useMutation({
    mutationFn: startLarkAuthorization,
  });
}

export function useStartLarkConfiguration() {
  return useMutation({
    mutationFn: startLarkConfiguration,
  });
}

export function useCompleteLarkConfiguration() {
  return useMutation({
    mutationFn: completeLarkConfiguration,
  });
}

export function useSetLarkAppCredentials() {
  return useMutation({
    mutationFn: setLarkAppCredentials,
  });
}

export function useCompleteLarkAuthorization() {
  return useMutation({
    mutationFn: completeLarkAuthorization,
  });
}
