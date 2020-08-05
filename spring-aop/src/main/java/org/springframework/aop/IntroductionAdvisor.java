/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.aop;

/**
 * Superinterface for advisors that perform one or more AOP <b>introductions</b>.
 *
 * <p>This interface cannot be implemented directly; subinterfaces must
 * provide the advice type implementing the introduction.
 *
 * <p>Introduction is the implementation of additional interfaces
 * (not implemented by a target) via AOP advice.
 *
 * @author Rod Johnson
 * @since 04.04.2003
 * @see IntroductionInterceptor
 *
 * Spring中有五种增强：
 *     BeforeAdvide（前置增强）、AfterAdvice（后置增强）、ThrowsAdvice（异常增强）、RoundAdvice（环绕增强）、IntroductionAdvice（引入增强）
 * RoundAdvice（环绕增强）：就是BeforeAdvide（前置增强）、AfterAdvice（后置增强）的组合使用叫环绕增强
 *
 * IntroductionAdvisor 和 PointcutAdvisor接口不同，它仅有一个类过滤器ClassFilter 而没有 MethodMatcher，这是因为 `引介切面 的切点是类级别的，而 Pointcut 的切点是方法级别的（细粒度更细，所以更加常用）。
 * Introduction可以在不改动目标类定义的情况下，为目标类增加新的属性和行为。
 * IntroductionInfo：引介信息,接口描述了目标类需要实现的新接口
 *
 * IntroductionAdvisor: 引介增强器
 * 只能应用于类级别的拦截,一个Java类，没有实现A接口，在不修改Java类的情况下，使其具备A接口的功能。（非常强大有木有，A不需要动代码，就能有别的功能，吊炸天有木有）
 *
 * IntroductionInterceptor：引介拦截器
 * 在Spring中，为目标对象添加新的属性和行为,必须声明相应的接口以及相应的实现。这样，再通过特定的拦截器将新的接口定义以及实现类中的逻辑附加到目标对象上。然后，目标对象（确切的说，是目标对象的代理对象）就拥有了新的状态和行为
 *
 * 这里面介绍这个非常强大的拦截器：IntroductionInterceptor它是对MethodInterceptor的一个扩展，同时他还继承了接口DynamicIntroductionAdvice
 * 通过DynamicIntroductionAdvice，可以界定当前的 IntroductionInterceptor为哪些接口提供相应的拦截功能。通过MethodInterceptor,IntroductionInterceptor 就可以处理新添加的接口上的方法调用了
 *
 * 
 */
public interface IntroductionAdvisor extends Advisor, IntroductionInfo {

	/**
	 * Return the filter determining which target classes this introduction
	 * should apply to.
	 * <p>This represents the class part of a pointcut. Note that method
	 * matching doesn't make sense to introductions.
	 * @return the class filter
	 */
	ClassFilter getClassFilter();

	/**
	 * Can the advised interfaces be implemented by the introduction advice?
	 * Invoked before adding an IntroductionAdvisor.
	 * @throws IllegalArgumentException if the advised interfaces can't be
	 * implemented by the introduction advice
	 */
	void validateInterfaces() throws IllegalArgumentException;

}
