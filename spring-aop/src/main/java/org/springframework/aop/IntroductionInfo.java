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
 * Interface supplying the information necessary to describe an introduction.
 *
 * <p>{@link IntroductionAdvisor IntroductionAdvisors} must implement this
 * interface. If an {@link org.aopalliance.aop.Advice} implements this,
 * it may be used as an introduction without an {@link IntroductionAdvisor}.
 * In this case, the advice is self-describing, providing not only the
 * necessary behavior, but describing the interfaces it introduces.
 *
 * @author Rod Johnson
 * @since 1.1.1
 */
public interface IntroductionInfo {

	/**
	 * Return the additional interfaces introduced by this Advisor or Advice.
	 * @return the introduced interfaces
	 * 引介(Introduction)不同，直接在类级别上添加目标未实现的接口方法
	 * 普通的advice只是在原有接口基础上增加附加内容
	 * introduction用于给target引入新的接口
	 *
	 * 完成一个introduction需要三个内容
	 * 1.将要添加的新接口的定义
	 * 2.该新接口的实现，必须实现IntroductionInterceptor接口
	 * 3.IntroductionInterceptor#validateInterfaces()必须返回true
	 */
	Class<?>[] getInterfaces();

}
