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

package org.springframework.context.event;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.expression.AnnotatedElementKey;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.context.expression.CachedExpressionEvaluator;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.lang.Nullable;

/**
 * Utility class for handling SpEL expression parsing for application events.
 * <p>Meant to be used as a reusable, thread-safe component.
 *
 * @author Stephane Nicoll
 * @since 4.2
 * @see CachedExpressionEvaluator
 */
class EventExpressionEvaluator extends CachedExpressionEvaluator {

	// ExpressionKey为CachedExpressionEvaluator的一个吧内部类
	// Expression为：org.springframework.expression.Expression 表达式
	private final Map<ExpressionKey, Expression> conditionCache = new ConcurrentHashMap<>(64);


	/**
	 * Determine if the condition defined by the specified expression evaluates
	 * to {@code true}.
	 */
	public boolean condition(String conditionExpression, ApplicationEvent event, Method targetMethod,
			AnnotatedElementKey methodKey, Object[] args, @Nullable BeanFactory beanFactory) {
		// EventExpressionRootObject就是简单的持有传入的两个变量的引用而已~~~
		// 这个RootObject是我们#root值的来源~~~~
		EventExpressionRootObject root = new EventExpressionRootObject(event, args);

		// 准备一个执行上下文。关于SpEL的执行上文，请参照下面的链接
		// 这个执行上下文是处理此问题的重中之重~~~~下面会有解释
		// getParameterNameDiscoverer 它能够根据方法参数列表的名称取值~~~强大
		// 同时也支持a0、a1... 和 p0、p1...等等都直接取值~~这个下面会为何会支持得这么强大的原因~~~
		MethodBasedEvaluationContext evaluationContext = new MethodBasedEvaluationContext(
				root, targetMethod, args, getParameterNameDiscoverer());
		if (beanFactory != null) {
			evaluationContext.setBeanResolver(new BeanFactoryResolver(beanFactory));
		}

		// 可以看到  请让表达式的值返回bool值~~~~~~~
		// getExpression是父类的~  最终有用的就一句话：expr = getParser().parseExpression(expression);
		// 默认采用的是SpelExpressionParser这个解析器解析这个表达式
		return (Boolean.TRUE.equals(getExpression(this.conditionCache, methodKey, conditionExpression).getValue(
				evaluationContext, Boolean.class)));
	}

}
