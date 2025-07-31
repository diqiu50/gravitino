package org.apache.gravitino.integration.test.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.DisabledIf;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@DisabledIf("org.apache.gravitino.integration.test.util.ITUtils#isEmbedded")
public @interface DisabledIfEmbedded {}
