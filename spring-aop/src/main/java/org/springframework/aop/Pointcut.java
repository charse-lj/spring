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
 * Core Spring pointcut abstraction.
 *
 * <p>A pointcut is composed of a {@link ClassFilter} and a {@link MethodMatcher}.
 * Both these basic terms and a Pointcut itself can be combined to build up combinations
 * (e.g. through {@link org.springframework.aop.support.ComposablePointcut}).
 *
 * @author Rod Johnson
 * @see ClassFilter
 * @see MethodMatcher
 * @see org.springframework.aop.support.Pointcuts
 * @see org.springframework.aop.support.ClassFilters
 * @see org.springframework.aop.support.MethodMatchers
 * 切点，决定advice应该作用于那个连接点，比如根据正则等规则匹配哪些方法需要增强;（Pointcut 目前有getClassFilter（类匹配），getMethodMatcher（方法匹配），Pointcut TRUE （全匹配））
 *
 * 11(10+1)种类型的表达式
 * 1.execution：一般用于指定方法的执行，用的最多。
 *     execution(modifiers-pattern? ret-type-pattern declaring-type-pattern? name-pattern(param-pattern) throws-pattern?)
 *     修饰符匹配（modifier-pattern?）
 *     返回值匹配（return-type-pattern）可以为*表示任何返回值,全路径的类名等
 *     类路径匹配（declaring-type-pattern?）
 *     方法名匹配（name-pattern）可以指定方法名 或者 代表所有, set 代表以set开头的所有方法
 *     参数匹配（(param-pattern)）可以指定具体的参数类型，多个参数间用“,”隔开，各个参数也可以用“”来表示匹配任意类型的参数，如(String)表示匹配一个String参数的方法；(,String) 表示匹配有两个参数的方法，第一个参数可以是任意类型，而第二个参数是String类型；可以用(…)表示零个或多个任意参数
 *     异常类型匹配（throws-pattern?）
 *     其中后面跟着“?”的是可选项
 *     //表示匹配所有方法
 * 	   1）execution(* *(..))
 *     //表示匹配com.fsx.run.UserService中所有的公有方法
 *     2）execution(public * com.fsx.run.UserService.*(..))
 *     //表示匹配com.fsx.run包及其子包下的所有方法
 *     3）execution(* com.fsx.run..*.*(..))
 *   Pointcut定义时，还可以使用&&、||、! 这三个运算。进行逻辑运算
 *     // 签名：消息发送切面
 *     @Pointcut("execution(* com.fsx.run.MessageSender.*(..))")
 *     private void logSender(){}
 *     // 签名：消息接收切面
 *     @Pointcut("execution(* com.fsx.run.MessageReceiver.*(..))")
 *     private void logReceiver(){}
 *     // 只有满足发送  或者  接收  这个切面都会切进去
 *     @Pointcut("logSender() || logReceiver()")
 *     private void logMessage(){}

 * 2.within：指定某些类型的全部方法执行，也可用来指定一个包。
 *     within是用来指定类型的，指定类型中的所有方法将被拦截。
 *     // AService下面所有外部调用方法，都会拦截。备注：只能是AService的方法，子类不会拦截的
 *     @Pointcut("within(com.fsx.run.service.AService)")
 *         public void pointCut() {
 *     }
 *     所以此处需要注意：上面写的是AService接口，是达不到拦截效果的，只能写实现类：
 *     匹配包以及子包内的所有类
 *     @Pointcut("within(com.fsx.run.service..*)")
 *         public void pointCut() {
 *     }
 * 3.this：Spring Aop是基于动态代理的，生成的bean也是一个代理对象，this就是这个代理对象，当这个对象可以转换为指定的类型时，对应的切入点就是它了，Spring Aop将生效
 *    this类型的Pointcut表达式的语法是this(type)，当生成的代理对象可以转换为type指定的类型时则表示匹配
 *    // 这样子，就可以拦截到AService所有的子类的所有外部调用方法
 *     @Pointcut("this(com.fsx.run.service.AService*)")
 *         public void pointCut() {
 *     }
 * 4.target：当被代理的对象可以转换为指定的类型时，对应的切入点就是它了，Spring Aop将生效。
 *     Spring Aop是基于代理的，target则表示被代理的目标对象。当被代理的目标对象可以被转换为指定的类型时则表示匹配。
 *     注意：和上面不一样，这里是target，因此如果要切入，只能写实现类了
 *     @Pointcut("target(com.fsx.run.service.impl.AServiceImpl)")
 *         public void pointCut() {
 *     }
 * 5.args：当执行的方法的参数是指定类型时生效。
 * 		1、“args()”匹配任何不带参数的方法。
 *     2、“args(java.lang.String)”匹配任何只带一个参数，而且这个参数的类型是String的方法。
 *     3、“args(…)”带任意参数的方法。
 *     4、“args(java.lang.String,…)”匹配带任意个参数，但是第一个参数的类型是String的方法。
 *     5、“args(…,java.lang.String)”匹配带任意个参数，但是最后一个参数的类型是String的方法。
 * 6.@target：当代理的目标对象上拥有指定的注解时生效。
 * 		@target匹配当被代理的目标对象对应的类型及其父类型上拥有指定的注解时。
 * 		//能够切入类上（非方法上）标准了MyAnno注解的所有外部调用方法
 *     @Pointcut("@target(com.fsx.run.anno.MyAnno)")
 *         public void pointCut() {
 *     }
 * 7.@args：当执行的方法参数类型上拥有指定的注解时生效。
 * 		@args匹配被调用的方法上含有参数，且对应的参数类型上拥有指定的注解的情况。
 * 		// 匹配**方法参数类型上**拥有MyAnno注解的方法调用。如我们有一个方法add(MyParam param)接收一个MyParam类型的参数，而MyParam这个类是拥有注解MyAnno的，则它可以被Pointcut表达式匹配上
 *     @Pointcut("@args(com.fsx.run.anno.MyAnno)")
 *         public void pointCut() {
 *     }
 * 8.@within：与@target类似，看官方文档和网上的说法都是@within只需要目标对象的类或者父类上有指定的注解，则@within会生效，而@target则是必须是目标对象的类上有指定的注解。而根据笔者的测试这两者都是只要目标类或父类上有指定的注解即可。
 *      @within用于匹配被代理的目标对象对应的类型或其父类型拥有指定的注解的情况，但只有在调用拥有指定注解的类上的方法时才匹配。
 *      @within(com.fsx.run.anno.MyAnno)”匹配被调用的方法声明的类上拥有MyAnno注解的情况。比如有一个ClassA上使用了注解MyAnno标注，并且定义了一个方法a()，那么在调用ClassA.a()方法时将匹配该Pointcut；如果有一个ClassB上没有MyAnno注解，但是它继承自ClassA，同时它上面定义了一个方法b()，那么在调用ClassB().b()方法时不会匹配该Pointcut，但是在调用ClassB().a()时将匹配该方法调用，因为a()是定义在父类型ClassA上的，且ClassA上使用了MyAnno注解。但是如果子类ClassB覆写了父类ClassA的a()方法，则调用ClassB.a()方法时也不匹配该Pointcut。
 * 9.@annotation：当执行的方法上拥有指定的注解时生效。
 * 		@annotation用于匹配方法上拥有指定注解的情况。
 * 	   // 可以匹配所有方法上标有此注解的方法
 *     @Pointcut("@annotation(com.fsx.run.anno.MyAnno)")
 *         public void pointCut() {
 *     }
 *     我们还可以这么写，非常方便的获取到方法上面的注解
 *     @Before("@annotation(myAnno)")
 *     public void doBefore(JoinPoint joinPoint, MyAnno myAnno) {
 *         System.out.println(myAnno); //@com.fsx.run.anno.MyAnno()
 *         System.out.println("AOP Before Advice...");
 *     }
 * 10.reference pointcut：(经常使用)表示引用其他命名切入点，只有@ApectJ风格支持，Schema风格不支持
 *  @Aspect
 * 	public class HelloAspect {
 *     @Pointcut("execution(* com.fsx.service.*.*(..)) ")
 *     public void point() {
 *     }
 *     // 这个就是一个`reference pointcut`  甚至还可以这样 @Before("point1() && point2()")
 *     @Before("point()")
 *     public void before() {
 *         System.out.println("this is from HelloAspect#before...");
 *     }
 *   }
 * 11.bean：当调用的方法是指定的bean的方法时生效。(Spring AOP自己扩展支持的)
 * 		bean用于匹配当调用的是指定的Spring的某个bean的方法时。
 *     1、“bean(abc)”匹配Spring Bean容器中id或name为abc的bean的方法调用。
 *     2、“bean(user*)”匹配所有id或name为以user开头的bean的方法调用。
 *      // 这个就能切入到AServiceImpl类的素有的外部调用的方法里
 *     @Pointcut("bean(AServiceImpl)")
 *     public void pointCut() {
 *     }
 * Pointcut定义时，还可以使用&&、||、! 这三个运算。进行逻辑运算。可以把各种条件组合起来使用
 * *：匹配任何数量字符；
 * …：匹配任何数量字符的重复，如在类型模式中匹配任何数量子包；而在方法参数模式中匹配任何数量参数。
 * +：匹配指定类型的子类型；仅能作为后缀放在类型模式后边。
 *
 * 类型匹配语法
 * java.lang.String    匹配String类型；
 * java.*.String       匹配java包下的任何“一级子包”下的String类型； 如匹配java.lang.String，但不匹配java.lang.ss.String
 * java..*            匹配java包及任何子包下的任何类型。如匹配java.lang.String、java.lang.annotation.Annotation
 * java.lang.*ing      匹配任何java.lang包下的以ing结尾的类型；
 * java.lang.Number+  匹配java.lang包下的任何Number的子类型； 如匹配java.lang.Integer，也匹配java.math.BigInteger 
 * {@link org.aspectj.weaver.tools.PointcutPrimitive }
 *
 * 表达式的组合
 * 表达式的组合其实就是对应的表达式的逻辑运算，与、或、非。可以通过它们把多个表达式组合在一起。
 * 1、“bean(userService) && args()”匹配id或name为userService的bean的所有无参方法。
 * 2、“bean(userService) || @annotation(MyAnnotation)”匹配id或name为userService的bean的方法调用，或者是方法上使用了MyAnnotation注解的方法调用。
 * 3、“bean(userService) && !args()”匹配id或name为userService的bean的所有有参方法调用。
 */
public interface Pointcut {

	/**
	 * Return the ClassFilter for this pointcut.
	 * @return the ClassFilter (never {@code null})
	 */
	ClassFilter getClassFilter();

	/**
	 * Return the MethodMatcher for this pointcut.
	 * @return the MethodMatcher (never {@code null})
	 */
	MethodMatcher getMethodMatcher();


	/**
	 * Canonical Pointcut instance that always matches.
	 */
	Pointcut TRUE = TruePointcut.INSTANCE;

}
