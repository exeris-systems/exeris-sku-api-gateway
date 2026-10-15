package eu.exeris.sku.gateway.paths;

import java.util.Collection;

/**
 * The one comparison every path exclusion uses: a request target matches an excluded path when the part
 * before the first {@code ?} or {@code #} equals it exactly.
 *
 * <p>The comparison runs on index ranges of the request target, so nothing is allocated: the target is
 * never cut into a path and a query. A policy that excluded by a different rule would let the same request
 * through one gate and stop it at the next.
 */
public final class PathMatch {

    private PathMatch() {
    }

    /**
     * Copies the excluded paths into the array form {@link #matchesAny} iterates without an iterator.
     *
     * @param excludedPaths the exact paths to exclude, or {@code null} for none
     * @return a private copy
     */
    public static String[] toArray(Collection<String> excludedPaths) {
        return excludedPaths == null ? new String[0] : excludedPaths.toArray(String[]::new);
    }

    /**
     * @param target   the request target, with or without a query or fragment; {@code null} matches nothing
     * @param excluded an exact path
     * @return whether the path part of {@code target} equals {@code excluded}
     */
    public static boolean matches(String target, String excluded) {
        if (target == null) {
            return false;
        }
        int end = pathEnd(target);
        return end == excluded.length() && target.startsWith(excluded);
    }

    /**
     * @param target   the request target; {@code null} matches nothing
     * @param excluded exact paths
     * @return whether the path part of {@code target} equals any of them
     */
    public static boolean matchesAny(String target, String[] excluded) {
        if (target == null) {
            return false;
        }
        int end = pathEnd(target);
        for (String path : excluded) {
            if (end == path.length() && target.startsWith(path)) {
                return true;
            }
        }
        return false;
    }

    private static int pathEnd(String target) {
        for (int i = 0, n = target.length(); i < n; i++) {
            char c = target.charAt(i);
            if (c == '?' || c == '#') {
                return i;
            }
        }
        return target.length();
    }
}
