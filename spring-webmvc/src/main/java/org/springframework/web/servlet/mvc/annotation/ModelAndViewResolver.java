/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.web.servlet.mvc.annotation;

import java.lang.reflect.Method;

import org.springframework.lang.Nullable;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.ModelAndView;

/**
 * SPI for resolving custom return values from a specific handler method.
 * Typically implemented to detect special return types, resolving
 * well-known result values for them.
 *
 * <p>A typical implementation could look like as follows:
 *
 * <pre class="code">
 * public class MyModelAndViewResolver implements ModelAndViewResolver {
 *
 *     public ModelAndView resolveModelAndView(Method handlerMethod, Class handlerType,
 *             Object returnValue, ExtendedModelMap implicitModel, NativeWebRequest webRequest) {
 *         if (returnValue instanceof MySpecialRetVal.class)) {
 *             return new MySpecialRetVal(returnValue);
 *         }
 *         return UNRESOLVED;
 *     }
 * }</pre>
 *
 * @author Arjen Poutsma
 * @since 3.0
 *
 * ModelAndViewResolver它是一个接口，Spring并没有默认的实现类。
 * Spring对它的定位很清楚：SPI for resolving custom return values from a specific handler method，
 * 它就是给我们自己来自定义处理返回值的一个处理器。通常用于检测特殊的返回类型，解析它们的已知结果值
 */
public interface ModelAndViewResolver {

	/**
	 * Marker to be returned when the resolver does not know how to handle the given method parameter.
	 */
	ModelAndView UNRESOLVED = new ModelAndView();


	ModelAndView resolveModelAndView(Method handlerMethod, Class<?> handlerType,
			@Nullable Object returnValue, ExtendedModelMap implicitModel, NativeWebRequest webRequest);

}
