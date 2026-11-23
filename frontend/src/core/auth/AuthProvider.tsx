"use client";

import { useRouter, usePathname } from "next/navigation";
import React, {
  createContext,
  useContext,
  useState,
  useCallback,
  useEffect,
  type ReactNode,
} from "react";

import { isStaticWebsiteOnly } from "../static-mode";

import { type User, buildLoginUrl } from "./types";

const SESSION_CHANGE = "deerflow-session-change";

export function notifySessionChange(change: "logout" | "login") {
  try {
    localStorage.setItem(SESSION_CHANGE, `${change}:${Date.now()}:${Math.random()}`);
  } catch {
    // Tabs also revalidate with the gateway when they become visible.
  }
}

// Re-export for consumers
export type { User };

/**
 * Authentication context provided to consuming components
 */
interface AuthContextType {
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
  applyUser: (user: User | null) => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

interface AuthProviderProps {
  children: ReactNode;
  initialUser: User | null;
}

/**
 * AuthProvider - Unified authentication context for the application
 *
 * Per RFC-001:
 * - Only holds display information (user), never JWT or tokens
 * - initialUser comes from server-side guard, avoiding client flicker
 * - Provides logout and refresh capabilities
 */
export function AuthProvider({ children, initialUser }: AuthProviderProps) {
  const [user, setUser] = useState<User | null>(initialUser);
  const [isLoading, setIsLoading] = useState(false);
  const [peerSessionChanged, setPeerSessionChanged] = useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const staticMode = isStaticWebsiteOnly();

  const authRequest = React.useRef(0);
  const isAuthenticated = user !== null;

  /**
   * Apply a user value supplied by a caller (e.g. banner probe) that has
   * already fetched it. Equivalent to setUser, exposed with a stable name
   * so consumers don't reach into React internals.
   */
  const applyUser = useCallback((next: User | null) => {
    authRequest.current++;
    setUser(next);
    setIsLoading(false);
  }, []);

  /**
   * Fetch current user from FastAPI
   * Used when initialUser might be stale (e.g., after tab was inactive)
   */
  const refreshUser = useCallback(async () => {
    if (staticMode) return;

    const request = ++authRequest.current;
    try {
      setIsLoading(true);
      const res = await fetch("/api/v1/auth/me", {
        credentials: "include",
        cache: "no-store",
      });
      if (request !== authRequest.current) return;

      if (res.ok) {
        const data = await res.json();
        if (request === authRequest.current) setUser(data);
      } else {
        // Session expired or invalid
        setUser(null);
        // Redirect to login if on a protected route
        if (res.status === 401 && pathname?.startsWith("/workspace")) {
          router.push(buildLoginUrl(pathname));
        }
      }
    } catch (err) {
      if (request !== authRequest.current) return;
      console.error("Failed to refresh user:", err);
      setUser(null);
    } finally {
      if (request === authRequest.current) setIsLoading(false);
    }
  }, [staticMode, pathname, router]);

  /**
   * End the authenticated document when signing out. A client-side route
   * change would preserve query caches and in-flight responses across logins.
   */
  const logout = useCallback(async () => {
    applyUser(null);

    if (staticMode) {
      router.push("/");
      return;
    }

    notifySessionChange("logout");
    try {
      await fetch("/api/v1/auth/logout", {
        method: "POST",
        credentials: "include",
      });
    } catch (err) {
      console.error("Logout request failed:", err);
    }

    window.location.replace("/");
  }, [applyUser, staticMode, router]);

  useEffect(() => {
    if (staticMode || !pathname?.startsWith("/workspace")) return;

    const onSessionChange = (event: StorageEvent) => {
      if (event.key !== SESSION_CHANGE) return;
      setPeerSessionChanged(true);
      applyUser(null);
      if (event.newValue?.startsWith("login:")) void refreshUser();
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") void refreshUser();
    };
    window.addEventListener("storage", onSessionChange);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("storage", onSessionChange);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [applyUser, pathname, refreshUser, staticMode]);

  const value: AuthContextType = {
    user,
    isAuthenticated,
    isLoading,
    logout,
    refreshUser,
    applyUser,
  };

  // During a peer sign-out, mounting workspace consumers with no account
  // would issue protected requests and redirect this tab away from its page.
  const pauseWorkspace = peerSessionChanged && !user && pathname?.startsWith("/workspace");
  return (
    <AuthContext.Provider value={value}>
      {pauseWorkspace ? null : children}
    </AuthContext.Provider>
  );
}

/**
 * Hook to access authentication context
 * Throws if used outside AuthProvider - this is intentional for proper usage
 */
export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}

/**
 * Hook to require authentication - redirects to login if not authenticated
 * Useful for client-side checks in addition to server-side guards
 */
export function useRequireAuth(): AuthContextType {
  const auth = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (isStaticWebsiteOnly()) return;

    // Only redirect if we're sure user is not authenticated (not just loading)
    if (!auth.isLoading && !auth.isAuthenticated) {
      router.push(buildLoginUrl(pathname || "/workspace"));
    }
  }, [auth.isAuthenticated, auth.isLoading, router, pathname]);

  return auth;
}
