/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.scheduling.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that marks a method as a candidate for <i>asynchronous</i> execution.
 * Can also be used at the type level, in which case all of the type's methods are
 * considered as asynchronous. Note, however, that {@code @Async} is not supported
 * on methods declared within a
 * {@link org.springframework.context.annotation.Configuration @Configuration} class.
 *
 * <p>In terms of target method signatures, any parameter types are supported.
 * However, the return type is constrained to either {@code void} or
 * {@link java.util.concurrent.Future}. In the latter case, you may declare the
 * more specific {@link org.springframework.util.concurrent.ListenableFuture} or
 * {@link java.util.concurrent.CompletableFuture} types which allow for richer
 * interaction with the asynchronous task and for immediate composition with
 * further processing steps.
 *
 * <p>A {@code Future} handle returned from the proxy will be an actual asynchronous
 * {@code Future} that can be used to track the result of the asynchronous method
 * execution. However, since the target method needs to implement the same signature,
 * it will have to return a temporary {@code Future} handle that just passes a value
 * through: e.g. Spring's {@link AsyncResult}, EJB 3.1's {@link javax.ejb.AsyncResult},
 * or {@link java.util.concurrent.CompletableFuture#completedFuture(Object)}.
 *
 * @author Juergen Hoeller
 * @author Chris Beams
 * @since 3.0
 * @see AnnotationAsyncExecutionInterceptor
 * @see AsyncAnnotationAdvisor
 *  注解使用细节
 * 1.@Async注解一般用在方法上，如果用在类上，那么这个类所有的方法都是异步执行的
 * 2.@Async可以放在任何方法上，哪怕你是private的（若是同类调用，请务必注意注解失效的情况~~~）
 * 3.所使用的@Async注解方法的类对象应该是Spring容器管理的bean对象
 * 4.@Async可以放在接口处（或者接口方法上）。但是只有使用的是JDK的动态代理时才有效，CGLIB会失效。因此建议：统一写在实现类的方法上
 * 5.需要注解@EnableAsync来开启异步注解的支持
 * 6.若你希望得到异步调用的返回值，请你的返回值用Futrue变量包装起来
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Async {

	/**
	 * A qualifier value for the specified asynchronous operation(s).
	 * <p>May be used to determine the target executor to be used when executing
	 * the asynchronous operation(s), matching the qualifier value (or the bean
	 * name) of a specific {@link java.util.concurrent.Executor Executor} or
	 * {@link org.springframework.core.task.TaskExecutor TaskExecutor}
	 * bean definition.
	 * <p>When specified on a class-level {@code @Async} annotation, indicates that the
	 * given executor should be used for all methods within the class. Method-level use
	 * of {@code Async#value} always overrides any value set at the class level.
	 * @since 3.1.2
	 * 这个value值是用来指定执行器的（写入执行器BeanName即可采用特定的执行器去执行此方法）
	 */
	String value() default "";

}
