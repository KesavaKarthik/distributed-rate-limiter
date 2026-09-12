package com.ratelimiter.filter;

import com.ratelimiter.security.IdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-phase ordering, asserted on the beans Boot actually registers rather
 * than on the constants that were meant to produce it.
 *
 * <p>Both halves matter. The coarse limit ahead of authentication is what lets
 * unauthenticated flood traffic be shed before any auth work is spent on it. The
 * fine limit behind authentication is what makes a per-user rule possible at all
 * — before that point there is no user to resolve a rule for.
 */
@SpringBootTest
class FilterOrderTest {

    @Autowired
    private List<OncePerRequestFilter> filters;

    @Test
    void coarseLimitRunsBeforeAuthentication_fineLimitAfterIt() {
        List<Class<?>> chain = orderedFilterClasses();

        int coarse = chain.indexOf(IpRateLimitFilter.class);
        int auth = chain.indexOf(IdentityFilter.class);
        int fine = chain.indexOf(IdentityRateLimitFilter.class);

        assertTrue(coarse >= 0 && auth >= 0 && fine >= 0, "all three filters must be registered: " + chain);
        assertTrue(coarse < auth, "the per-IP limit must shed traffic before auth work is spent on it");
        assertTrue(auth < fine, "the per-user limit cannot resolve a rule before identity exists");
    }

    private List<Class<?>> orderedFilterClasses() {
        List<OncePerRequestFilter> ordered = new ArrayList<>(filters);
        AnnotationAwareOrderComparator.sort(ordered);

        List<Class<?>> classes = new ArrayList<>();
        for (OncePerRequestFilter filter : ordered) {
            classes.add(filter.getClass());
        }
        return classes;
    }
}
