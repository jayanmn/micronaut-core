/*
 * Copyright 2017 original authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.particleframework.function.groovy

import groovy.transform.CompileStatic
import groovy.transform.Field
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.tools.GenericsUtils
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.FieldASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.particleframework.ast.groovy.InjectTransform
import org.particleframework.ast.groovy.utils.AstAnnotationUtils
import org.particleframework.ast.groovy.utils.AstMessageUtils
import org.particleframework.ast.groovy.utils.AstUtils
import org.particleframework.context.ApplicationContext
import org.particleframework.context.env.groovy.SetPropertyTransformer
import org.particleframework.core.naming.NameUtils
import org.particleframework.function.FunctionBean

import java.lang.reflect.Modifier
import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import static org.codehaus.groovy.ast.tools.GeneralUtils.*

/**
 * Transforms a Groovy script into a function
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
class FunctionTransform implements ASTTransformation{
    public static final ClassNode FIELD_TYPE = ClassHelper.make(Field)

    @Override
    void visit(ASTNode[] nodes, SourceUnit source) {

        def uri = source.getSource().getURI()
        if(uri != null) {
            def file = uri.toString()
            if( !file.endsWith("Function.groovy") && !file.toLowerCase(Locale.ENGLISH).endsWith("-function.groovy")) {
                return
            }
        }
        for(node in source.getAST().classes) {
            if(node.isScript()) {
                node.setSuperClass(ClassHelper.makeCached(FunctionScript))
                MethodNode functionMethod = node.methods.find() { method -> !method.isAbstract() &&  !method.isStatic() && method.isPublic() && method.name != 'run' }
                if(functionMethod == null) {
                    AstMessageUtils.error(source, node, "Function must have at least one public method")
                }
                else {


                    MethodNode runMethod = node.getMethod("run", AstUtils.ZERO_PARAMETERS)
                    node.removeMethod(runMethod)
                    MethodNode mainMethod = node.getMethod("main", new Parameter(ClassHelper.make(([] as String[]).class), "args"))
                    Parameter argParam = mainMethod.getParameters()[0]
                    def thisInstance = varX('$this')
                    def parameters = functionMethod.parameters
                    int argLength = parameters.length
                    boolean isVoidReturn = functionMethod.returnType == ClassHelper.VOID_TYPE

                    if(argLength > 2) {
                        AstMessageUtils.error(source, node, "Functions can only have a maximum of 2 arguments")
                        continue
                    }
                    else if(argLength == 0 && isVoidReturn) {
                        AstMessageUtils.error(source, node, "Zero argument functions must return a value")
                        continue
                    }

                    MethodCallExpression functionCall

                    if(argLength == 1) {
                        functionCall = callX(thisInstance, functionMethod.getName(), args(callX(varX("it"), "get", args(classX(parameters[0].type.plainNodeReference)))))
                    }
                    else {
                        functionCall = callX(thisInstance, functionMethod.getName())
                    }

                    def closureExpression = closureX(stmt(functionCall))
                    mainMethod.variableScope.putDeclaredVariable(thisInstance)
                    closureExpression.setVariableScope(mainMethod.variableScope)
                    mainMethod.setCode(
                            block(
                                declS(thisInstance, ctorX(node)),
                                stmt(callX(thisInstance, "run", args(varX(argParam), closureExpression)))
                            )
                    )
                    def code = runMethod.getCode()
                    def appCtx = varX("applicationContext")
                    def constructorBody = block(

                    )
                    if(code instanceof BlockStatement) {
                        BlockStatement bs = (BlockStatement)code
                        for(st in bs.statements) {
                            if(st instanceof ExpressionStatement) {
                                ExpressionStatement es = (ExpressionStatement)st
                                Expression exp = es.expression
                                if(exp instanceof DeclarationExpression) {
                                    DeclarationExpression de = (DeclarationExpression)exp
                                    def initial = de.getVariableExpression().getInitialExpression()
                                    if ( initial == null) {
                                        de.addAnnotation(new AnnotationNode(AstUtils.INJECT_ANNOTATION))
                                        new FieldASTTransformation().visit([new AnnotationNode(FIELD_TYPE), de] as ASTNode[], source)
                                    }
                                }
                                else if(exp instanceof BinaryExpression || exp instanceof MethodCallExpression) {
                                    def setPropertyTransformer = new SetPropertyTransformer(source)
                                    setPropertyTransformer.setPropertyMethodName = "addProperty"
                                    constructorBody.addStatement(
                                            stmt(setPropertyTransformer.transform(exp))
                                    )
                                }
                            }
                        }
                    }

                    constructorBody.addStatement(block(
                            stmt(
                                    callX(varX("this"), "startEnvironment", appCtx)
                            ),
                            stmt(
                                    callX(appCtx, "inject", varX("this"))
                            )
                    ))
                    ConstructorNode constructorNode = new ConstructorNode(Modifier.PUBLIC, constructorBody)
                    node.declaredConstructors.clear()
                    node.addConstructor(constructorNode)
                    def ctxParam = param(ClassHelper.make(ApplicationContext), "ctx")

                    def applicationContextConstructor = new ConstructorNode(
                            Modifier.PUBLIC,
                            params(ctxParam),
                            null,
                            stmt(
                                ctorX(ClassNode.SUPER, varX(ctxParam))
                            )

                    )
                    for(field in node.getFields()) {
                        field.addAnnotation(new AnnotationNode(AstUtils.INJECT_ANNOTATION))
                        def setterName = getSetterName(field.getName())
                        def setterMethod = node.getMethod(setterName, params(param(field.getType(), "arg")))
                        if(setterMethod != null) {
                            setterMethod.addAnnotation(new AnnotationNode(AstUtils.INTERNAL_ANNOTATION))
                        }
                    }

                    applicationContextConstructor.addAnnotation(new AnnotationNode(AstUtils.INJECT_ANNOTATION))
                    def functionBean = new AnnotationNode(ClassHelper.make(FunctionBean))
                    String functionName = NameUtils.hyphenate(node.nameWithoutPackage)
                    functionName -= '-function'

                    functionBean.setMember("value", constX(functionName))
                    node.addAnnotation(functionBean)
                    node.addConstructor(
                            applicationContextConstructor
                    )

                    if(isVoidReturn) {
                        if(argLength == 1) {
                            implementConsumer(functionMethod, node)
                        }
                        else {
                            implementBiConsumer(functionMethod, node)
                        }

                    }
                    else {
                        if(argLength == 0) {
                            def returnType = ClassHelper.getWrapper(functionMethod.returnType.plainNodeReference)
                            node.addInterface(GenericsUtils.makeClassSafeWithGenerics(
                                    ClassHelper.make(Supplier).plainNodeReference,
                                    new GenericsType(returnType)
                            ))
                            def mn = new MethodNode("get", Modifier.PUBLIC, functionMethod.returnType.plainNodeReference, AstUtils.ZERO_PARAMETERS, null, stmt(
                                    callX(varX("this"), functionMethod.getName())
                            ))
                            mn.addAnnotation(new AnnotationNode(AstUtils.INTERNAL_ANNOTATION))
                            node.addMethod(mn)
                        }
                        else {
                            if(argLength == 1) {
                                implementFunction(functionMethod, node)
                            }
                            else {
                                implementBiFunction(functionMethod, node)
                            }

                        }
                    }
                    AstAnnotationUtils.invalidateCache()
                    new InjectTransform().visit(nodes, source)
                }

            }
        }
    }

    protected void implementConsumer(MethodNode functionMethod, ClassNode classNode) {
        implementFunction(functionMethod, classNode, Consumer, ClassHelper.VOID_TYPE, "accept")
    }

    protected void implementBiConsumer(MethodNode functionMethod, ClassNode classNode) {
        implementFunction(functionMethod, classNode, BiConsumer, ClassHelper.VOID_TYPE, "accept")
    }

    protected void implementFunction(MethodNode functionMethod, ClassNode classNode) {
        ClassNode returnType = ClassHelper.getWrapper(functionMethod.returnType.plainNodeReference)
        implementFunction(functionMethod, classNode, Function, returnType, "apply")
    }

    protected void implementBiFunction(MethodNode functionMethod, ClassNode classNode) {
        ClassNode returnType = ClassHelper.getWrapper(functionMethod.returnType.plainNodeReference)
        implementFunction(functionMethod, classNode, BiFunction, returnType, "apply")
    }

    protected void implementFunction(MethodNode functionMethod, ClassNode classNode, Class functionType, ClassNode returnType, String methodName) {
        List<ClassNode> argTypes = []
        for(p in functionMethod.parameters) {
            argTypes.add(ClassHelper.getWrapper(p.type.plainNodeReference))
        }
        List<GenericsType> genericsTypes = []
        for(type in argTypes) {
            genericsTypes.add(new GenericsType(type))
        }
        if(returnType != ClassHelper.VOID_TYPE) {
            genericsTypes.add(new GenericsType(returnType))
        }
        classNode.addInterface(GenericsUtils.makeClassSafeWithGenerics(
                ClassHelper.make(functionType).plainNodeReference,
                genericsTypes as GenericsType[]
        ))
        List<Parameter> params = []
        int i = 0
        ArgumentListExpression argList = new ArgumentListExpression()
        for(type in argTypes) {
            def p = param(type, "arg${i++}")
            params.add(p)
            argList.addExpression(varX(p))
        }
        def mn = new MethodNode(methodName, Modifier.PUBLIC, returnType, params as Parameter[], null, stmt(
                callX(varX("this"), functionMethod.getName(), argList))
        )
        mn.addAnnotation(new AnnotationNode(AstUtils.INTERNAL_ANNOTATION))
        classNode.addMethod(mn)
    }


}