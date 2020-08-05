/*
 * Copyright 2002-2017 the original author or authors.
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

import org.aopalliance.aop.Advice;

/**
 * Base interface holding AOP <b>advice</b> (action to take at a joinpoint)
 * and a filter determining the applicability of the advice (such as
 * a pointcut). <i>This interface is not for use by Spring users, but to
 * allow for commonality in support for different types of advice.</i>
 *
 * <p>Spring AOP is based around <b>around advice</b> delivered via method
 * <b>interception</b>, compliant with the AOP Alliance interception API.
 * The Advisor interface allows support for different types of advice,
 * such as <b>before</b> and <b>after</b> advice, which need not be
 * implemented using interception.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * Advisor 是Spring AOP的顶层抽象，用来管理Advice和Pointcut
 * {@link PointcutAdvisor}:和切点有关
 * {@link IntroductionAdvisor}:和切点无关）
 * Pointcut是Spring AOP对切点的抽象。切点的实现方式有多种，其中一种就是AspectJ
 *
 * 它的继承体系主要有如下两个：PointcutAdvisor和IntroductionAdvisor
 * 最本质上的区别就是：
 * 		IntroductionAdvisor只能应用于类级别的拦截，只能使用Introduction型的Advice
 * 		PointcutAdvisor那样，可以使用任何类型的Pointcut，以及几乎任何类型的Advice
 *
 * PointcutAdvisor：和切点有关的Advisor
 * AbstractPointcutAdvisor：抽象实现
 * AbstractGenericPointcutAdvisor：一般的、通用的PointcutAdvisor
 * DefaultPointcutAdvisor：通用的，最强大的Advisor
 * AbstractBeanFactoryPointcutAdvisor：和bean工厂有关的PointcutAdvisor
 * DefaultBeanFactoryPointcutAdvisor：通用的BeanFactory的Advisor
 */
public interface Advisor {

	/**
	 * Common placeholder for an empty {@code Advice} to be returned from
	 * {@link #getAdvice()} if no proper advice has been configured (yet).
	 * @since 5.0
	 */
	Advice EMPTY_ADVICE = new Advice() {};


	/**
	 * Return the advice part of this aspect. An advice may be an
	 * interceptor, a before advice, a throws advice, etc.
	 * @return the advice that should apply if the pointcut matches
	 * @see org.aopalliance.intercept.MethodInterceptor
	 * @see BeforeAdvice
	 * @see ThrowsAdvice
	 * @see AfterReturningAdvice
	 *
	 * 该Advisor 持有的通知器
	 */
	Advice getAdvice();

	/**
	 * Return whether this advice is associated with a particular instance
	 * (for example, creating a mixin) or shared with all instances of
	 * the advised class obtained from the same Spring bean factory.
	 * <p><b>Note that this method is not currently used by the framework.</b>
	 * Typical Advisor implementations always return {@code true}.
	 * Use singleton/prototype bean definitions or appropriate programmatic
	 * proxy creation to ensure that Advisors have the correct lifecycle model.
	 * @return whether this advice is associated with a particular target instance
	 *
	 * 这个有点意思：Spring所有的实现类都是return true(官方说暂时还没有应用到)
	 * 注意：生成的Advisor是单例还是多例不由isPerInstance()的返回结果决定，而由自己在定义bean的时候控制
	 * 理解：和类共享（per-class）或基于实例（per-instance）相关  类共享：类比静态变量   实例共享：类比实例变量
	 */
	boolean isPerInstance();

}
