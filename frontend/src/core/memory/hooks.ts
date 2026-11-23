import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { useAuth } from "@/core/auth/AuthProvider";

import {
  clearMemory,
  createMemoryFact,
  deleteMemoryFact,
  importMemory,
  loadMemory,
  updateMemoryFact,
} from "./api";
import type {
  MemoryFactInput,
  MemoryFactPatchInput,
  UserMemory,
} from "./types";

function useMemoryQueryKey() {
  const { user } = useAuth();
  return ["memory", user?.id] as const;
}

export function useMemory() {
  const queryKey = useMemoryQueryKey();
  const { data, isLoading, error } = useQuery({
    queryKey,
    enabled: Boolean(queryKey[1]),
    refetchOnWindowFocus: false,
    queryFn: () => loadMemory(),
  });
  return { memory: data ?? null, isLoading, error };
}

export function useClearMemory() {
  const queryClient = useQueryClient();
  const queryKey = useMemoryQueryKey();

  return useMutation({
    mutationFn: () => clearMemory(),
    onSuccess: (memory) => {
      queryClient.setQueryData<UserMemory>(queryKey, memory);
    },
  });
}

export function useDeleteMemoryFact() {
  const queryClient = useQueryClient();
  const queryKey = useMemoryQueryKey();

  return useMutation({
    mutationFn: (factId: string) => deleteMemoryFact(factId),
    onSuccess: (memory) => {
      queryClient.setQueryData<UserMemory>(queryKey, memory);
    },
  });
}

export function useImportMemory() {
  const queryClient = useQueryClient();
  const queryKey = useMemoryQueryKey();

  return useMutation({
    mutationFn: (memory: UserMemory) => importMemory(memory),
    onSuccess: (memory) => {
      queryClient.setQueryData<UserMemory>(queryKey, memory);
    },
  });
}

export function useCreateMemoryFact() {
  const queryClient = useQueryClient();
  const queryKey = useMemoryQueryKey();

  return useMutation({
    mutationFn: (input: MemoryFactInput) => createMemoryFact(input),
    onSuccess: (memory) => {
      queryClient.setQueryData<UserMemory>(queryKey, memory);
    },
  });
}

export function useUpdateMemoryFact() {
  const queryClient = useQueryClient();
  const queryKey = useMemoryQueryKey();

  return useMutation({
    mutationFn: ({
      factId,
      input,
    }: {
      factId: string;
      input: MemoryFactPatchInput;
    }) => updateMemoryFact(factId, input),
    onSuccess: (memory) => {
      queryClient.setQueryData<UserMemory>(queryKey, memory);
    },
  });
}
