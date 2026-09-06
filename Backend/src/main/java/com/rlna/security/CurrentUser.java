package com.rlna.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated {@link com.rlna.entity.User} into a controller
 * method.
 *
 * <p>Controllers take this instead of a user id parameter, which is what makes
 * it structurally impossible for a caller to nominate whose data they are
 * asking for.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
