package dev.saq.mediscan.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the caller's {@link dev.saq.mediscan.session.OwnerRef} into a controller method.
 *
 * <p>This is how report code gets an owner without ever reading the security context itself
 * ({@code CLAUDE.md} rule 7, LLD 6.5). A handler declares what it needs - "whoever is
 * calling, as an owner" - and the resolver works out whether that is a guest session or a
 * user, and refuses the request if it is neither.
 *
 * <pre>
 * &#64;GetMapping("/{id}")
 * ReportResponse find(&#64;CurrentOwner OwnerRef owner, &#64;PathVariable UUID id) { ... }
 * </pre>
 *
 * @see CurrentOwnerArgumentResolver
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentOwner {
}
