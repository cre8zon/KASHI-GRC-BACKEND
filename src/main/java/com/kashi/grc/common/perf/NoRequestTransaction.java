package com.kashi.grc.common.perf;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a GET handler (or a whole controller) out of the request-wide
 * transaction RequestTransactionAspect opens around every GET.
 *
 * Use it for a GET that does long non-database work — calling an external
 * service, streaming a large file — so it does not hold a pooled database
 * connection for that time. Streaming / async / file-bytes return types are
 * already skipped automatically.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoRequestTransaction {
}