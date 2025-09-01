package com.tencent.supersonic.headless.server.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DefaultDimValueCheck {
}
