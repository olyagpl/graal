/*
 * Copyright (c) 2016, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.instrumentation.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.InstrumentInfo;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AllocationEvent;
import com.oracle.truffle.api.instrumentation.AllocationEventFilter;
import com.oracle.truffle.api.instrumentation.AllocationListener;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.EventContext;
import com.oracle.truffle.api.instrumentation.ExecuteSourceEvent;
import com.oracle.truffle.api.instrumentation.ExecuteSourceListener;
import com.oracle.truffle.api.instrumentation.ExecutionEventListener;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Instrumenter;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionEvent;
import com.oracle.truffle.api.instrumentation.LoadSourceSectionListener;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.SourceFilter;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Registration;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

public class InstrumentationTest extends AbstractInstrumentationTest {

    /*
     * Test that metadata is properly propagated to Instrument handles.
     */
    @Test
    public void testMetadata() {
        Instrument instrumentHandle1 = engine.getInstruments().get("testMetadataType1");

        Assert.assertEquals("name", instrumentHandle1.getName());
        Assert.assertEquals("version", instrumentHandle1.getVersion());
        Assert.assertEquals("testMetadataType1", instrumentHandle1.getId());
        Assert.assertFalse(isInitialized(instrumentHandle1));
        Assert.assertFalse(isCreated(instrumentHandle1));
    }

    @Registration(name = "name", version = "version", id = "testMetadataType1")
    public static class MetadataInstrument extends TruffleInstrument {
        @Override
        protected void onCreate(Env env) {
        }
    }

    @Registration(name = "name", version = "version", id = "testBrokenRegistration", services = Runnable.class)
    public static class BrokenRegistrationInstrument extends TruffleInstrument {
        @Override
        protected void onCreate(Env env) {
        }
    }

    @Test
    public void forgetsToRegisterADeclaredService() throws Exception {
        Instrument handle = engine.getInstruments().get("testBrokenRegistration");
        assertNotNull(handle);
        Runnable r = handle.lookup(Runnable.class);
        assertNull("The service isn't there", r);
        if (!err.toString().contains("declares service java.lang.Runnable but doesn't register it")) {
            fail(err.toString());
        }
    }

    @Registration(name = "name", version = "version", id = "beforeUse", services = Runnable.class)
    public static class BeforeUseInstrument extends TruffleInstrument implements Runnable {
        private Env env;

        @Override
        protected void onCreate(Env anEnv) {
            this.env = anEnv;
            this.env.registerService(this);
        }

        @Override
        public void run() {
            LanguageInfo info = env.getLanguages().get(InstrumentationTestLanguage.ID);
            SpecialService ss = env.lookup(info, SpecialService.class);
            assertNotNull("Service found", ss);
            assertEquals("The right extension", ss.fileExtension(), InstrumentationTestLanguage.FILENAME_EXTENSION);

            assertNull("Can't query object", env.lookup(info, Object.class));
            assertNull("Can't query language", env.lookup(info, TruffleLanguage.class));
        }

    }

    @Test
    public void queryInstrumentsBeforeUseAndObtainSpecialService() throws Exception {
        engine = Engine.newBuilder().err(err).build();
        context.enter();
        Runnable start = null;
        for (Instrument instr : engine.getInstruments().values()) {
            Runnable r = instr.lookup(Runnable.class);
            if (r != null) {
                start = r;
                start.run();
                assertTrue("Now enabled: " + instr, isCreated(instr));
            }
        }
        context.leave();
        assertNotNull("At least one Runnable found", start);
    }

    @Test(expected = IllegalStateException.class) // IllegalStateException: Engine is already closed
    public void queryInstrumentsAfterDisposeDoesnotEnable() throws Exception {
        engine = Engine.newBuilder().err(err).build();
        engine.close();
        Runnable start = null;
        for (Instrument instr : engine.getInstruments().values()) {
            assertFalse("Instrument is disabled", isInitialized(instr));

            Runnable r = instr.lookup(Runnable.class);
            if (r != null) {
                start = r;
                start.run();
                assertTrue("Now enabled: " + instr, isCreated(instr));
            }
            assertFalse("Instrument left disabled", isInitialized(instr));
        }
        assertNull("No Runnable found", start);
    }

    /*
     * Test that metadata is properly propagated to Instrument handles.
     */
    @Test
    public void testDefaultId() {
        Instrument descriptor1 = engine.getInstruments().get(MetadataInstrument2.class.getSimpleName());
        Assert.assertEquals("", descriptor1.getName());
        Assert.assertEquals(engine.getVersion(), descriptor1.getVersion());
        Assert.assertEquals(MetadataInstrument2.class.getSimpleName(), descriptor1.getId());
        Assert.assertFalse(isInitialized(descriptor1));
    }

    @Registration
    public static class MetadataInstrument2 extends TruffleInstrument {
        @Override
        protected void onCreate(Env env) {
        }
    }

    /*
     * Test onCreate, onFinalize and onDispose invocations for multiple instrument instances.
     */
    @Test
    public void testMultipleInstruments() throws IOException {
        run(""); // initialize

        MultipleInstanceInstrument.onCreateCounter = 0;
        MultipleInstanceInstrument.onFinalizeCounter = 0;
        MultipleInstanceInstrument.onDisposeCounter = 0;
        MultipleInstanceInstrument.constructor = 0;
        Instrument instrument1 = engine.getInstruments().get("testMultipleInstruments");
        cleanup();
        Assert.assertEquals(0, MultipleInstanceInstrument.constructor);
        Assert.assertEquals(0, MultipleInstanceInstrument.onCreateCounter);
        Assert.assertEquals(0, MultipleInstanceInstrument.onFinalizeCounter);
        Assert.assertEquals(0, MultipleInstanceInstrument.onDisposeCounter);

        engine = getEngine();
        MultipleInstanceInstrument.onCreateCounter = 0;
        instrument1 = engine.getInstruments().get("testMultipleInstruments");
        instrument1.lookup(Object.class); // enabled
        Assert.assertEquals(1, MultipleInstanceInstrument.constructor);
        Assert.assertEquals(1, MultipleInstanceInstrument.onCreateCounter);
        Assert.assertEquals(0, MultipleInstanceInstrument.onFinalizeCounter);
        Assert.assertEquals(0, MultipleInstanceInstrument.onDisposeCounter);

        Instrument instrument2 = engine.getInstruments().get("testMultipleInstruments");
        instrument2.lookup(Object.class); // the same enabled
        Assert.assertEquals(1, MultipleInstanceInstrument.constructor);
        Assert.assertEquals(1, MultipleInstanceInstrument.onCreateCounter);
        Assert.assertEquals(0, MultipleInstanceInstrument.onFinalizeCounter);
        Assert.assertEquals(0, MultipleInstanceInstrument.onDisposeCounter);

        engine.close();
        engine = null;
        Assert.assertEquals(1, MultipleInstanceInstrument.constructor);
        Assert.assertEquals(1, MultipleInstanceInstrument.onCreateCounter);
        Assert.assertEquals(1, MultipleInstanceInstrument.onFinalizeCounter);
        Assert.assertEquals(1, MultipleInstanceInstrument.onDisposeCounter);
    }

    @Registration(id = "testMultipleInstruments", services = Object.class)
    public static class MultipleInstanceInstrument extends TruffleInstrument {

        private static int onCreateCounter = 0;
        private static int onFinalizeCounter = 0;
        private static int onDisposeCounter = 0;
        private static int constructor = 0;

        public MultipleInstanceInstrument() {
            constructor++;
        }

        @Override
        protected void onCreate(Env env) {
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
            onCreateCounter++;
        }

        @Override
        protected void onFinalize(Env env) {
            assertEquals(0, onDisposeCounter); // no dispose yet
            onFinalizeCounter++;
        }

        @Override
        protected void onDispose(Env env) {
            assertEquals(1, onFinalizeCounter); // finalized already
            onDisposeCounter++;
        }
    }

    /*
     * Test exceptions from language instrumentation are not wrapped into InstrumentationExceptions.
     * Test that one language cannot instrument another.
     */
    @Test
    public void testLanguageInstrumentationAndExceptions() throws IOException {
        TestLanguageInstrumentationLanguage.installInstrumentsCounter = 0;
        TestLanguageInstrumentationLanguage.createContextCounter = 0;
        try {
            context.eval(Source.create("test-language-instrumentation-language", "ROOT(EXPRESSION)"));
            Assert.fail("expected exception");
        } catch (PolyglotException ex) {
            // we assert that MyLanguageException is not wrapped
            assertEquals(MyLanguageException.class.getName(), ex.getMessage());
        }
        Assert.assertEquals(1, TestLanguageInstrumentationLanguage.installInstrumentsCounter);
        Assert.assertEquals(1, TestLanguageInstrumentationLanguage.createContextCounter);

        // this should run isolated from the language instrumentation.
        run("STATEMENT");
    }

    @SuppressWarnings("serial")
    private static class MyLanguageException extends RuntimeException {

    }

    @TruffleLanguage.Registration(id = "test-language-instrumentation-language", name = "", version = "")
    @ProvidedTags({StandardTags.ExpressionTag.class, StandardTags.StatementTag.class})
    public static class TestLanguageInstrumentationLanguage extends InstrumentationTestLanguage {

        static int installInstrumentsCounter = 0;
        static int createContextCounter = 0;

        public TestLanguageInstrumentationLanguage() {
        }

        private static void installInstruments(Instrumenter instrumenter) {
            installInstrumentsCounter++;
            instrumenter.attachExecutionEventListener(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.EXPRESSION).build(), new ExecutionEventListener() {
                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                }

                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                }

                public void onEnter(EventContext context, VirtualFrame frame) {
                    // since we are a language instrumentation we can throw exceptions
                    // without getting wrapped into Instrumentation exception.
                    throw new MyLanguageException();
                }
            });

            instrumenter.attachExecutionEventListener(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.STATEMENT).build(), new ExecutionEventListener() {
                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                    throw new AssertionError();
                }

                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                    throw new AssertionError();
                }

                public void onEnter(EventContext context, VirtualFrame frame) {
                    throw new AssertionError();
                }
            });
        }

        @Override
        protected InstrumentContext createContext(com.oracle.truffle.api.TruffleLanguage.Env env) {
            createContextCounter++;
            Instrumenter instrumenter = env.lookup(Instrumenter.class);
            Assert.assertNotNull("Instrumenter found", instrumenter);
            installInstruments(instrumenter);
            return super.createContext(env);
        }

        @Override
        protected CallTarget parse(ParsingRequest request) {
            return Truffle.getRuntime().createCallTarget(new RootNode(this) {

                @Child private BaseNode base = parse(request.getSource());

                @Override
                public Object execute(VirtualFrame frame) {
                    return base.execute(frame);
                }
            });
        }

        @Override
        protected boolean isObjectOfLanguage(Object object) {
            return false;
        }

    }

    @Test
    public void testInstrumentException1() {
        try {
            assureEnabled(engine.getInstruments().get("testInstrumentException1"));
            Assert.fail();
        } catch (PolyglotException e) {
            Assert.assertTrue(e.getMessage().contains("MyLanguageException"));
        }
    }

    @Registration(name = "", version = "", id = "testInstrumentException1", services = Object.class)
    public static class TestInstrumentException1 extends TruffleInstrument {

        @Override
        protected void onCreate(Env env) {
            throw new MyLanguageException();
        }

        @Override
        protected void onDispose(Env env) {
        }
    }

    /*
     * We test that instrumentation exceptions are wrapped, onReturnExceptional is invoked properly
     * and not onReturnValue,
     */
    @Test
    public void testInstrumentException2() throws IOException {
        TestInstrumentException2.returnedExceptional = 0;
        TestInstrumentException2.returnedValue = 0;
        assureEnabled(engine.getInstruments().get("testInstrumentException2"));
        try {
            run("ROOT(EXPRESSION)");
            Assert.fail("No exception was thrown.");
        } catch (PolyglotException ex) {
            Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("MyLanguageException"));
        }
        Assert.assertEquals(1, TestInstrumentException2.returnedExceptional);
        Assert.assertEquals(0, TestInstrumentException2.returnedValue);
    }

    @Registration(name = "", version = "", id = "testInstrumentException2", services = Object.class)
    public static class TestInstrumentException2 extends TruffleInstrument {

        static int returnedExceptional = 0;
        static int returnedValue = 0;

        @Override
        protected void onCreate(Env env) {
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.EXPRESSION).build(), new ExecutionEventListener() {

                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                    returnedValue++;
                }

                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                    returnedExceptional++;
                }

                public void onEnter(EventContext context, VirtualFrame frame) {
                    throw new MyLanguageException();
                }
            });
        }

        @Override
        protected void onDispose(Env env) {
        }
    }

    /*
     * Test that instrumentation exceptions in the onReturnExceptional are attached as suppressed
     * exceptions.
     */
    @Test
    public void testInstrumentException3() throws IOException {
        TestInstrumentException3.returnedExceptional = 0;
        TestInstrumentException3.onEnter = 0;
        assureEnabled(engine.getInstruments().get("testInstrumentException3"));
        try {
            run("ROOT(EXPRESSION)");
            Assert.fail("No exception was thrown.");
        } catch (PolyglotException ex) {
            Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("MyLanguageException"));
        }
        Assert.assertEquals(0, TestInstrumentException3.returnedExceptional);
        Assert.assertEquals(1, TestInstrumentException3.onEnter);
    }

    @Registration(name = "", version = "", id = "testInstrumentException3", services = Object.class)
    public static class TestInstrumentException3 extends TruffleInstrument {

        static int returnedExceptional = 0;
        static int onEnter = 0;

        @Override
        protected void onCreate(Env env) {
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.EXPRESSION).build(), new ExecutionEventListener() {

                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                    throw new MyLanguageException();
                }

                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                    returnedExceptional++;
                }

                public void onEnter(EventContext context, VirtualFrame frame) {
                    onEnter++;
                }
            });
        }

    }

    /*
     * Test that event nodes are created lazily on first execution.
     */
    @Test
    public void testLazyProbe1() throws IOException {
        TestLazyProbe1.createCalls = 0;
        TestLazyProbe1.onEnter = 0;
        TestLazyProbe1.onReturnValue = 0;
        TestLazyProbe1.onReturnExceptional = 0;

        assureEnabled(engine.getInstruments().get("testLazyProbe1"));
        run("ROOT(DEFINE(foo, EXPRESSION))");
        run("ROOT(DEFINE(bar, ROOT(EXPRESSION,EXPRESSION)))");

        Assert.assertEquals(0, TestLazyProbe1.createCalls);
        Assert.assertEquals(0, TestLazyProbe1.onEnter);
        Assert.assertEquals(0, TestLazyProbe1.onReturnValue);
        Assert.assertEquals(0, TestLazyProbe1.onReturnExceptional);

        run("ROOT(CALL(foo))");

        Assert.assertEquals(1, TestLazyProbe1.createCalls);
        Assert.assertEquals(1, TestLazyProbe1.onEnter);
        Assert.assertEquals(1, TestLazyProbe1.onReturnValue);
        Assert.assertEquals(0, TestLazyProbe1.onReturnExceptional);

        run("ROOT(CALL(bar))");

        Assert.assertEquals(3, TestLazyProbe1.createCalls);
        Assert.assertEquals(3, TestLazyProbe1.onEnter);
        Assert.assertEquals(3, TestLazyProbe1.onReturnValue);
        Assert.assertEquals(0, TestLazyProbe1.onReturnExceptional);

        run("ROOT(CALL(bar))");

        Assert.assertEquals(3, TestLazyProbe1.createCalls);
        Assert.assertEquals(5, TestLazyProbe1.onEnter);
        Assert.assertEquals(5, TestLazyProbe1.onReturnValue);
        Assert.assertEquals(0, TestLazyProbe1.onReturnExceptional);

        run("ROOT(CALL(foo))");

        Assert.assertEquals(3, TestLazyProbe1.createCalls);
        Assert.assertEquals(6, TestLazyProbe1.onEnter);
        Assert.assertEquals(6, TestLazyProbe1.onReturnValue);
        Assert.assertEquals(0, TestLazyProbe1.onReturnExceptional);

    }

    @Registration(name = "", version = "", id = "testLazyProbe1", services = Object.class)
    public static class TestLazyProbe1 extends TruffleInstrument {

        static int createCalls = 0;
        static int onEnter = 0;
        static int onReturnValue = 0;
        static int onReturnExceptional = 0;

        @Override
        protected void onCreate(Env env) {
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
            env.getInstrumenter().attachExecutionEventFactory(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.EXPRESSION).build(), new ExecutionEventNodeFactory() {
                public ExecutionEventNode create(EventContext context) {
                    createCalls++;
                    return new ExecutionEventNode() {
                        @Override
                        public void onReturnValue(VirtualFrame frame, Object result) {
                            onReturnValue++;
                        }

                        @Override
                        public void onReturnExceptional(VirtualFrame frame, Throwable exception) {
                            onReturnExceptional++;
                        }

                        @Override
                        public void onEnter(VirtualFrame frame) {
                            onEnter++;
                        }
                    };
                }
            });
        }
    }

    /*
     * Test that parsing and executing foreign languages work.
     */
    @Test
    public void testEnvParse1() throws IOException {
        TestEnvParse1.onExpression = 0;
        TestEnvParse1.onStatement = 0;

        assureEnabled(engine.getInstruments().get("testEnvParse1"));
        run("STATEMENT");

        Assert.assertEquals(1, TestEnvParse1.onExpression);
        Assert.assertEquals(1, TestEnvParse1.onStatement);

        run("STATEMENT");

        Assert.assertEquals(2, TestEnvParse1.onExpression);
        Assert.assertEquals(2, TestEnvParse1.onStatement);
    }

    @Registration(name = "", version = "", id = "testEnvParse1", services = Object.class)
    public static class TestEnvParse1 extends TruffleInstrument {

        static int onExpression = 0;
        static int onStatement = 0;

        @Override
        protected void onCreate(final Env env) {
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
            env.getInstrumenter().attachExecutionEventFactory(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.STATEMENT).build(), new ExecutionEventNodeFactory() {
                public ExecutionEventNode create(EventContext context) {

                    final CallTarget target;
                    try {
                        target = env.parse(com.oracle.truffle.api.source.Source.newBuilder(InstrumentationTestLanguage.ID, "EXPRESSION", "unknown").build());
                    } catch (Exception e) {
                        throw new AssertionError();
                    }

                    return new ExecutionEventNode() {
                        @Child private DirectCallNode directCall = Truffle.getRuntime().createDirectCallNode(target);

                        @Override
                        public void onEnter(VirtualFrame frame) {
                            onStatement++;
                            directCall.call(new Object[0]);
                        }

                    };
                }
            });

            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.EXPRESSION).build(), new ExecutionEventListener() {

                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                }

                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                }

                public void onEnter(EventContext context, VirtualFrame frame) {
                    onExpression++;
                }
            });

        }
    }

    /*
     * Test that inline parsing and executing works.
     */
    @Test
    public void testEnvParseInline() throws IOException {
        TestEnvParse1.onExpression = 0;
        TestEnvParse1.onStatement = 0;

        assureEnabled(engine.getInstruments().get("testEnvParseInline"));
        run("STATEMENT");

        Assert.assertEquals(1, TestEnvParseInline.onExpression);
        Assert.assertEquals(1, TestEnvParseInline.onStatement);

        run("STATEMENT");

        Assert.assertEquals(2, TestEnvParseInline.onExpression);
        Assert.assertEquals(2, TestEnvParseInline.onStatement);
    }

    @Registration(name = "", version = "", id = "testEnvParseInline", services = Object.class)
    public static class TestEnvParseInline extends TruffleInstrument {

        static int onExpression = 0;
        static int onStatement = 0;

        @Override
        protected void onCreate(final Env env) {
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
            env.getInstrumenter().attachExecutionEventFactory(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.STATEMENT).build(), new ExecutionEventNodeFactory() {
                @Override
                public ExecutionEventNode create(EventContext context) {

                    final ExecutableNode exec;
                    final ExecutableNode execOtherLang;

                    // Try to parse a buggy source:
                    try {
                        env.parseInline(
                                        com.oracle.truffle.api.source.Source.newBuilder(InstrumentationTestLanguage.ID, "some garbage", null).build(),
                                        context.getInstrumentedNode(), null);
                        Assert.fail("Should not be able to parse a garbage");
                    } catch (Exception e) {
                        // O.K.
                        assertTrue(e.getMessage(), e.getMessage().contains("Illegal tag \"some\""));
                    }
                    boolean otherLangSuccess = false;
                    // Try to parse a wrong language:
                    try {
                        env.parseInline(
                                        com.oracle.truffle.api.source.Source.newBuilder(TestOtherLanguageParseInline.ID, "EXPRESSION", null).build(),
                                        context.getInstrumentedNode(), null);
                        otherLangSuccess = true;
                    } catch (AssertionError e) {
                        // O.K.
                    }
                    assertFalse(otherLangSuccess);
                    TruffleLanguage<?> fakeOtherLanguage;
                    try {
                        fakeOtherLanguage = (TruffleLanguage<?>) env.parse(
                                        com.oracle.truffle.api.source.Source.newBuilder(TestOtherLanguageParseInline.ID, "EXPRESSION", null).build()).call();
                    } catch (IOException e) {
                        throw new AssertionError(e.getLocalizedMessage(), e);
                    }
                    // Create an ExecutableNode of a wrong language:
                    execOtherLang = new ExecutableNode(fakeOtherLanguage) {
                        @Override
                        public Object execute(VirtualFrame frame) {
                            assertNotNull(getParent());
                            return "";
                        }
                    };
                    // Do the correct inline parsing finally:
                    try {
                        exec = env.parseInline(
                                        com.oracle.truffle.api.source.Source.newBuilder(InstrumentationTestLanguage.ID, "EXPRESSION", null).build(),
                                        context.getInstrumentedNode(), null);
                    } catch (Exception e) {
                        throw new AssertionError(e.getLocalizedMessage(), e);
                    }

                    return new ExecutionEventNode() {

                        @Child private ExecutableNode exeNode;

                        @Override
                        public void onEnter(VirtualFrame frame) {
                            onStatement++;
                            if (exeNode == null) {
                                CompilerDirectives.transferToInterpreterAndInvalidate();
                                try {
                                    insert(execOtherLang);
                                    Assert.fail("Should not be able to insert an executable node of a different language!");
                                } catch (IllegalArgumentException ex) {
                                    // O.K.
                                }
                                exeNode = insert(exec);
                                notifyInserted(exeNode);
                            }
                            exeNode.execute(frame);
                        }

                    };
                }
            });

            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.EXPRESSION).build(), new ExecutionEventListener() {

                @Override
                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                }

                @Override
                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                }

                @Override
                public void onEnter(EventContext context, VirtualFrame frame) {
                    onExpression++;
                }
            });

        }
    }

    @TruffleLanguage.Registration(id = TestOtherLanguageParseInline.ID, name = "")
    public static class TestOtherLanguageParseInline extends InstrumentationTestLanguage {

        static final String ID = "testOtherParseInline-lang";

        @Override
        protected ExecutableNode parse(InlineParsingRequest request) throws Exception {
            return new ExecutableNode(this) {
                @Override
                public Object execute(VirtualFrame frame) {
                    assertNotNull(getParent());
                    return "Parsed by " + ID;
                }
            };
        }

        @Override
        protected CallTarget parse(ParsingRequest request) throws Exception {
            return new CallTarget() {
                @Override
                public Object call(Object... arguments) {
                    return TestOtherLanguageParseInline.this;
                }
            };
        }

    }

    /*
     * Test that inline parsing returns null by default.
     */
    @Test
    public void testParseInlineDefault() throws IOException {
        TestParseInlineDefault.executableNode = null;

        assureEnabled(engine.getInstruments().get("testParseInlineDefault"));
        Source source = Source.create(TestLanguageNoParseInline.ID, "STATEMENT");
        run(source);

        assertNull(TestParseInlineDefault.executableNode);

        run(source);

        assertNull(TestParseInlineDefault.executableNode);
    }

    @Registration(name = "", version = "", id = "testParseInlineDefault", services = Object.class)
    public static class TestParseInlineDefault extends TruffleInstrument {

        static ExecutableNode executableNode;

        @Override
        protected void onCreate(final Env env) {
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
            env.getInstrumenter().attachExecutionEventFactory(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.STATEMENT).build(), new ExecutionEventNodeFactory() {
                @Override
                public ExecutionEventNode create(EventContext context) {

                    ExecutableNode parsedNode = env.parseInline(
                                    com.oracle.truffle.api.source.Source.newBuilder(TestLanguageNoParseInline.ID, "EXPRESSION", null).build(),
                                    context.getInstrumentedNode(), null);
                    executableNode = parsedNode;
                    assertNull(parsedNode);
                    return new ExecutionEventNode() {
                    };
                }
            });
        }
    }

    @TruffleLanguage.Registration(id = TestLanguageNoParseInline.ID, name = "", version = "")
    public static class TestLanguageNoParseInline extends InstrumentationTestLanguage {

        static final String ID = "testNoParseInline-lang";

        @Override
        protected ExecutableNode parse(InlineParsingRequest request) throws Exception {
            return parseOriginal(request);
        }

    }

    @Test
    public void testReceiver() throws IOException {
        TestReceiver.Tester tester = engine.getInstruments().get("testReceiver").lookup(TestReceiver.Tester.class);
        Source source = Source.create(InstrumentationTestLanguage.ID,
                        "ROOT(DEFINE(foo1, ROOT(STATEMENT))," +
                                        "DEFINE(foo2, ROOT(CALL(foo1), STATEMENT))," +
                                        "CALL(foo1)," +
                                        "CALL_WITH(foo2, 42)," +
                                        "CALL_WITH(foo1, 43))");
        run(source);
        tester.assertReceivers(null, null, "42", "43");
    }

    @Registration(name = "", version = "", id = "testReceiver", services = TestReceiver.Tester.class)
    public static class TestReceiver extends TruffleInstrument {

        @Override
        protected void onCreate(final Env env) {
            final Tester tester = new Tester(env);
            env.registerService(tester);
            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.newBuilder().tagIs(StandardTags.StatementTag.class).build(), tester);
        }

        final class Tester implements ExecutionEventListener {

            private Env env;
            private final List<String> receiverObjects = new ArrayList<>();

            Tester(Env env) {
                this.env = env;
            }

            @Override
            public void onEnter(EventContext context, VirtualFrame frame) {
                addReceiverObject(context, frame.materialize());
            }

            @TruffleBoundary
            private void addReceiverObject(EventContext context, MaterializedFrame frame) {
                RootNode rootNode = context.getInstrumentedNode().getRootNode();
                Scope frameScope = env.findLocalScopes(rootNode, frame).iterator().next();
                Object receiver = frameScope.getReceiver();
                if (receiver != null) {
                    assertEquals("THIS", frameScope.getReceiverName());
                    receiverObjects.add(env.toString(rootNode.getLanguageInfo(), receiver));
                } else {
                    receiverObjects.add(null);
                }
            }

            @Override
            public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
            }

            @Override
            public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
            }

            private void assertReceivers(String... objects) {
                assertEquals(objects.length, receiverObjects.size());
                for (int i = 0; i < objects.length; i++) {
                    assertEquals(objects[i], receiverObjects.get(i));
                }
            }
        }
    }

    @Test
    public void testNearestExecutionNode() throws IOException {
        TestNearestExecutionNode.Tester tester = engine.getInstruments().get("testNearestExecutionNode").lookup(TestNearestExecutionNode.Tester.class);
        Source source = Source.create(InstrumentationTestLanguage.ID,
                        "ROOT(DEFINE(foo1, ROOT(STATEMENT, VARIABLE(a, 10), STATEMENT, EXPRESSION))," +
                                        "DEFINE(foo2, ROOT(EXPRESSION, CALL(foo1), STATEMENT, STATEMENT(EXPRESSION))))");
        tester.set(StandardTags.StatementTag.class, (offset, node) -> {
            int pos = node.getSourceSection().getCharIndex();
            if (offset <= 31) {
                return pos == 23;
            } else if (offset <= 75) {
                return pos == 51;
            } else if (offset <= 125) {
                return pos == 117;
            } else {
                return pos == 128;
            }
        });
        run(source);
        assertNull(tester.getFailures());
    }

    @Registration(name = "", version = "", id = "testNearestExecutionNode", services = TestNearestExecutionNode.Tester.class)
    public static class TestNearestExecutionNode extends TruffleInstrument {

        @Override
        protected void onCreate(final Env env) {
            final Tester tester = new Tester();
            env.registerService(tester);
            env.getInstrumenter().attachExecuteSourceListener(SourceFilter.ANY, new ExecuteSourceListener() {
                @Override
                public void onExecute(ExecuteSourceEvent event) {
                    int length = event.getSource().getLength();
                    env.getInstrumenter().attachLoadSourceSectionListener(SourceSectionFilter.ANY, new LoadSourceSectionListener() {
                        @Override
                        public void onLoad(LoadSourceSectionEvent evt) {
                            if (!(evt.getNode() instanceof InstrumentableNode)) {
                                return;
                            }
                            InstrumentableNode node = (InstrumentableNode) evt.getNode();
                            SourceSection ss = evt.getNode().getSourceSection();
                            if (ss == null || ss.getCharacters().toString().startsWith("ROOT(DEFINE")) {
                                // No SourceSection, or the outer function
                                return;
                            }
                            Class<? extends Tag> tag = tester.getTag();
                            Set<Class<? extends Tag>> tags = Collections.singleton(tag);
                            for (int offset = 0; offset < length; offset++) {
                                if (ss.getCharIndex() <= offset && offset < ss.getCharEndIndex()) {
                                    Node nearestNode = node.findNearestNodeAt(offset, tags);
                                    tester.checkNearest(offset, nearestNode);
                                }
                            }
                        }
                    }, true);
                }
            }, true);
        }

        static class Tester {

            private BiFunction<Integer, Node, Boolean> nearestNodeChecker;
            private Class<? extends Tag> tag;
            private List<String> failures;

            void set(Class<? extends Tag> tag, BiFunction<Integer, Node, Boolean> nearestNodeChecker) {
                this.tag = tag;
                this.nearestNodeChecker = nearestNodeChecker;
            }

            private Class<? extends Tag> getTag() {
                return tag;
            }

            private void checkNearest(int offset, Node nearestNode) {
                if (!nearestNodeChecker.apply(offset, nearestNode)) {
                    if (failures == null) {
                        failures = new ArrayList<>();
                    }
                    failures.add("Wrong nearest node for offset " + offset + ": " + nearestNode + " with section " + nearestNode.getSourceSection());
                }
            }

            String getFailures() {
                if (failures == null) {
                    return null;
                } else {
                    return failures.toString();
                }
            }
        }
    }

    /*
     * Test instrument all with any filter. Ensure that root nodes are not tried to be instrumented.
     */
    @Test
    public void testInstrumentAll() throws IOException {
        TestInstrumentAll1.onStatement = 0;

        assureEnabled(engine.getInstruments().get("testInstrumentAll"));
        run("STATEMENT");

        // An implicit root node + statement
        Assert.assertEquals(2, TestInstrumentAll1.onStatement);
    }

    @Registration(id = "testInstrumentAll", services = Object.class)
    public static class TestInstrumentAll1 extends TruffleInstrument {

        static int onStatement = 0;

        @Override
        protected void onCreate(final Env env) {
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.ANY, new ExecutionEventListener() {
                public void onEnter(EventContext context, VirtualFrame frame) {
                    onStatement++;
                }

                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                }

                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                }
            });
        }
    }

    /*
     * Define is not instrumentable but has a source section.
     */
    @Test
    public void testInstrumentNonInstrumentable() throws IOException {
        TestInstrumentNonInstrumentable1.onStatement = 0;

        assureEnabled(engine.getInstruments().get("testInstrumentNonInstrumentable"));
        run("DEFINE(foo, ROOT())");

        // DEFINE is not instrumentable, only ROOT is.
        Assert.assertEquals(1, TestInstrumentNonInstrumentable1.onStatement);
    }

    @Registration(id = "testInstrumentNonInstrumentable", services = Object.class)
    public static class TestInstrumentNonInstrumentable1 extends TruffleInstrument {

        static int onStatement = 0;

        @Override
        protected void onCreate(final Env env) {
            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.ANY, new ExecutionEventListener() {
                public void onEnter(EventContext context, VirtualFrame frame) {
                    onStatement++;
                }

                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                }

                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                }
            });
        }
    }

    @Test
    public void testOutputConsumer() throws IOException {
        // print without instruments
        String rout = run("PRINT(OUT, InitialToStdOut)");
        Assert.assertEquals("InitialToStdOut", rout);
        run("PRINT(ERR, InitialToStdErr)");
        Assert.assertEquals("InitialToStdErr", err.toString());
        err.reset();

        // turn instruments on
        assureEnabled(engine.getInstruments().get("testOutputConsumerArray"));
        assureEnabled(engine.getInstruments().get("testOutputConsumerPiped"));
        context.eval(lines("PRINT(OUT, OutputToStdOut)"));
        context.eval(lines("PRINT(ERR, OutputToStdErr)"));
        // test that the output goes eveywhere
        Assert.assertEquals("OutputToStdOut", getOut());
        Assert.assertEquals("OutputToStdOut", TestOutputConsumerArray.getOut());
        Assert.assertEquals("OutputToStdErr", getErr());
        Assert.assertEquals("OutputToStdErr", TestOutputConsumerArray.getErr());
        CharBuffer buff = CharBuffer.allocate(100);
        TestOutputConsumerPiped.fromOut.read(buff);
        buff.flip();
        Assert.assertEquals("OutputToStdOut", buff.toString());
        buff.rewind();
        TestOutputConsumerPiped.fromErr.read(buff);
        buff.flip();
        Assert.assertEquals("OutputToStdErr", buff.toString());
        buff.rewind();

        // close piped err stream and test that print still works
        TestOutputConsumerPiped.fromErr.close();
        context.eval(lines("PRINT(OUT, MoreOutputToStdOut)"));
        context.eval(lines("PRINT(ERR, MoreOutputToStdErr)"));
        Assert.assertEquals("OutputToStdOutMoreOutputToStdOut", out.toString());
        Assert.assertEquals("OutputToStdOutMoreOutputToStdOut", TestOutputConsumerArray.getOut());
        String errorMsg = "java.lang.Exception: Output operation write(B[II) failed for java.io.PipedOutputStream";
        Assert.assertTrue(err.toString(), err.toString().startsWith("OutputToStdErr" + errorMsg));
        Assert.assertTrue(err.toString(), err.toString().endsWith("MoreOutputToStdErr"));
        Assert.assertEquals("OutputToStdErrMoreOutputToStdErr", TestOutputConsumerArray.getErr());
        buff.limit(buff.capacity());
        TestOutputConsumerPiped.fromOut.read(buff);
        buff.flip();
        Assert.assertEquals("MoreOutputToStdOut", buff.toString());
        out.reset();
        err.reset();

        // the I/O error is not printed again
        context.eval(lines("PRINT(ERR, EvenMoreOutputToStdErr)"));
        Assert.assertEquals("EvenMoreOutputToStdErr", err.toString());
        Assert.assertEquals("OutputToStdErrMoreOutputToStdErrEvenMoreOutputToStdErr", TestOutputConsumerArray.getErr());

        // instruments disabled
        teardown();
        setup();
        out.reset();
        err.reset();
        context.eval(lines("PRINT(OUT, FinalOutputToStdOut)"));
        context.eval(lines("PRINT(ERR, FinalOutputToStdErr)"));
        Assert.assertEquals("FinalOutputToStdOut", out.toString());
        Assert.assertEquals("FinalOutputToStdErr", err.toString());
        // nothing more printed to the disabled instrument
        Assert.assertEquals("OutputToStdOutMoreOutputToStdOut", TestOutputConsumerArray.getOut());
        Assert.assertEquals("OutputToStdErrMoreOutputToStdErrEvenMoreOutputToStdErr", TestOutputConsumerArray.getErr());
    }

    @Registration(id = "testOutputConsumerArray", services = Object.class)
    public static class TestOutputConsumerArray extends TruffleInstrument {

        static ByteArrayOutputStream out = new ByteArrayOutputStream();
        static ByteArrayOutputStream err = new ByteArrayOutputStream();

        @Override
        protected void onCreate(Env env) {
            env.getInstrumenter().attachOutConsumer(out);
            env.getInstrumenter().attachErrConsumer(err);
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
        }

        static String getOut() {
            return new String(out.toByteArray());
        }

        static String getErr() {
            return new String(err.toByteArray());
        }
    }

    @Registration(id = "testOutputConsumerPiped", services = Object.class)
    public static class TestOutputConsumerPiped extends TruffleInstrument {

        static PipedOutputStream out = new PipedOutputStream();
        static Reader fromOut;
        static PipedOutputStream err = new PipedOutputStream();
        static Reader fromErr;

        @Override
        protected void onCreate(Env env) {
            try {
                fromOut = new InputStreamReader(new PipedInputStream(out));
                fromErr = new InputStreamReader(new PipedInputStream(err));
            } catch (IOException ex) {
                throw new AssertionError(ex.getLocalizedMessage(), ex);
            }
            env.getInstrumenter().attachOutConsumer(out);
            env.getInstrumenter().attachErrConsumer(err);
            // Not to get error: declares service, but doesn't register it
            env.registerService(new Object());
        }

        Reader fromOut() {
            return fromOut;
        }

        Reader fromErr() {
            return fromErr;
        }
    }

    /*
     * Tests for debugger or any other clients that cancel execution while halted
     */

    @Test
    public void testKillExceptionOnEnter() throws IOException {
        assureEnabled(engine.getInstruments().get("testKillQuitException"));
        TestKillQuitException.exceptionOnEnter = new MyKillException();
        TestKillQuitException.exceptionOnReturnValue = null;
        TestKillQuitException.returnExceptionalCount = 0;
        try {
            run("STATEMENT");
            Assert.fail("KillException in onEnter() cancels engine execution");
        } catch (PolyglotException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains(MyKillException.class.getName()));
        }
        Assert.assertEquals("KillException is not an execution event", 0, TestKillQuitException.returnExceptionalCount);
    }

    @Test
    public void testKillExceptionOnReturnValue() throws IOException {
        assureEnabled(engine.getInstruments().get("testKillQuitException"));
        TestKillQuitException.exceptionOnEnter = null;
        TestKillQuitException.exceptionOnReturnValue = new MyKillException();
        TestKillQuitException.returnExceptionalCount = 0;
        try {
            run("STATEMENT");
            Assert.fail("KillException in onReturnValue() cancels engine execution");
        } catch (PolyglotException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains(MyKillException.class.getName()));
        }
        Assert.assertEquals("KillException is not an execution event", 0, TestKillQuitException.returnExceptionalCount);
    }

    @Registration(id = "testKillQuitException", services = Object.class)
    public static class TestKillQuitException extends TruffleInstrument {

        static Error exceptionOnEnter = null;
        static Error exceptionOnReturnValue = null;
        static int returnExceptionalCount = 0;

        @Override
        protected void onCreate(final Env env) {
            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.ANY, new ExecutionEventListener() {
                public void onEnter(EventContext context, VirtualFrame frame) {
                    if (exceptionOnEnter != null) {
                        throw exceptionOnEnter;
                    }
                }

                public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                    if (exceptionOnReturnValue != null) {
                        throw exceptionOnReturnValue;
                    }
                }

                public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                    returnExceptionalCount++;
                }
            });
        }
    }

    /*
     * Use tags that are not declarded as required.
     */
    @Test
    @Ignore // InstrumentClientInstrumenter.verifyFilter() is empty
    public void testUsedTagNotRequired1() throws IOException {
        TestInstrumentNonInstrumentable1.onStatement = 0;

        assureEnabled(engine.getInstruments().get("testUsedTagNotRequired1"));
        run("ROOT()");

        Assert.assertEquals(0, TestInstrumentNonInstrumentable1.onStatement);
    }

    @Registration(id = "testUsedTagNotRequired1", services = Object.class)
    public static class TestUsedTagNotRequired1 extends TruffleInstrument {

        private static class Foobar {

        }

        @Override
        protected void onCreate(final Env env) {
            try {
                env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.newBuilder().tagIs(Foobar.class).build(), new ExecutionEventListener() {
                    public void onEnter(EventContext context, VirtualFrame frame) {
                    }

                    public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
                    }

                    public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
                    }
                });
                Assert.fail();
            } catch (IllegalArgumentException e) {
                Assert.assertEquals(
                                "The attached filter SourceSectionFilter[tag is one of [foobar0]] references the " +
                                                "following tags [foobar0] which are not declared as required by the instrument. To fix " +
                                                "this annotate the instrument class com.oracle.truffle.api.instrumentation." +
                                                "InstrumentationTest$TestUsedTagNotRequired1 with @RequiredTags({foobar0}).",
                                e.getMessage());
            }
        }
    }

    /*
     * Test behavior of queryTags when used with instruments
     */
    @Test
    public void testQueryTags1() throws IOException {
        Instrument instrument = engine.getInstruments().get("testIsNodeTaggedWith1");
        Instrumenter instrumenter = instrument.lookup(Instrumenter.class);

        TestIsNodeTaggedWith1.expressionNode = null;
        TestIsNodeTaggedWith1.statementNode = null;

        Assert.assertTrue(instrumenter.queryTags(new Node() {
        }).isEmpty());

        run("STATEMENT(EXPRESSION)");

        assertTags(instrumenter.queryTags(TestIsNodeTaggedWith1.expressionNode), InstrumentationTestLanguage.EXPRESSION);
        assertTags(instrumenter.queryTags(TestIsNodeTaggedWith1.statementNode), InstrumentationTestLanguage.STATEMENT);

        try {
            instrumenter.queryTags(null);
            Assert.fail();
        } catch (NullPointerException e) {
        }
    }

    private static void assertTags(Set<Class<?>> tags, Class<?>... expectedTags) {
        Assert.assertEquals(expectedTags.length, tags.size());
        for (Class<?> clazz : expectedTags) {
            Assert.assertTrue("Tag: " + clazz, tags.contains(clazz));
        }
    }

    /*
     * Test behavior of queryTags when used with languages
     */
    @Test
    public void testQueryTags2() throws IOException {
        Instrument instrument = engine.getInstruments().get("testIsNodeTaggedWith1");
        assureEnabled(instrument);
        TestIsNodeTaggedWith1.expressionNode = null;
        TestIsNodeTaggedWith1.statementNode = null;
        TestIsNodeTaggedWith1Language.instrumenter = null;

        Source otherLanguageSource = Source.create("testIsNodeTaggedWith1-lang", "STATEMENT(EXPRESSION)");
        run(otherLanguageSource);

        Instrumenter instrumenter = TestIsNodeTaggedWith1Language.instrumenter;

        Node languageExpression = TestIsNodeTaggedWith1.expressionNode;
        Node languageStatement = TestIsNodeTaggedWith1.statementNode;

        assertTags(instrumenter.queryTags(languageExpression), InstrumentationTestLanguage.EXPRESSION);
        assertTags(instrumenter.queryTags(languageStatement), InstrumentationTestLanguage.STATEMENT);

        TestIsNodeTaggedWith1.expressionNode = null;
        TestIsNodeTaggedWith1.statementNode = null;

        run("EXPRESSION");

        // fail if called with nodes from a different language
        Node otherLanguageExpression = TestIsNodeTaggedWith1.expressionNode;
        try {
            instrumenter.queryTags(otherLanguageExpression);
            Assert.fail();
        } catch (IllegalArgumentException e) {
        }

    }

    @Test
    public void testInstrumentsWhenForked() throws IOException {
        Instrument instrument = engine.getInstruments().get("testIsNodeTaggedWith1");
        assureEnabled(instrument);
        TestIsNodeTaggedWith1 service = instrument.lookup(TestIsNodeTaggedWith1.class);

        assertEquals(1, service.onCreateCalls);

        Source otherLanguageSource = Source.create("testIsNodeTaggedWith1-lang", "STATEMENT(EXPRESSION)");
        run(otherLanguageSource);

        org.graalvm.polyglot.Context forked = newContext();
        assertEquals(1, service.onCreateCalls);

        final Map<String, ? extends Instrument> instruments = forked.getEngine().getInstruments();
        assertSame(instrument, instruments.get("testIsNodeTaggedWith1"));
        assertSame(service, instruments.get("testIsNodeTaggedWith1").lookup(TestIsNodeTaggedWith1.class));

        assertEquals(instruments.size(), engine.getInstruments().size());
        for (String key : instruments.keySet()) {
            assertSame(engine.getInstruments().get(key), instruments.get(key));
        }

        assertEquals(0, service.onDisposeCalls);
        cleanup();
        assertEquals(0, service.onDisposeCalls);
        forked.getEngine().close();
        // test if all engines are disposed
        assertEquals(1, service.onDisposeCalls);
        engine = null; // avoid a second disposal in @After event
    }

    @TruffleLanguage.Registration(id = "testIsNodeTaggedWith1-lang", name = "")
    @ProvidedTags({StandardTags.ExpressionTag.class, StandardTags.StatementTag.class})
    public static class TestIsNodeTaggedWith1Language extends InstrumentationTestLanguage {

        static Instrumenter instrumenter;

        public TestIsNodeTaggedWith1Language() {
        }

        @Override
        protected InstrumentContext createContext(com.oracle.truffle.api.TruffleLanguage.Env env) {
            instrumenter = env.lookup(Instrumenter.class);
            return super.createContext(env);
        }

        @Override
        protected CallTarget parse(ParsingRequest request) {
            return Truffle.getRuntime().createCallTarget(new RootNode(this) {

                @Child private BaseNode base = parse(request.getSource());

                @Override
                public Object execute(VirtualFrame frame) {
                    return base.execute(frame);
                }
            });
        }

        @Override
        protected boolean isObjectOfLanguage(Object object) {
            return false;
        }

    }

    @Registration(id = "testIsNodeTaggedWith1", services = {Instrumenter.class, TestIsNodeTaggedWith1.class, Object.class})
    public static class TestIsNodeTaggedWith1 extends TruffleInstrument {

        static Node expressionNode;
        static Node statementNode;

        int onCreateCalls = 0;
        int onDisposeCalls = 0;

        @Override
        protected void onCreate(final Env env) {
            onCreateCalls++;
            env.registerService(this);
            env.registerService(env.getInstrumenter());
            env.getInstrumenter().attachExecutionEventFactory(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.EXPRESSION).build(), new ExecutionEventNodeFactory() {

                public ExecutionEventNode create(EventContext context) {
                    expressionNode = context.getInstrumentedNode();
                    return new ExecutionEventNode() {
                    };
                }
            });

            env.getInstrumenter().attachExecutionEventFactory(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.STATEMENT).build(), new ExecutionEventNodeFactory() {

                public ExecutionEventNode create(EventContext context) {
                    statementNode = context.getInstrumentedNode();
                    return new ExecutionEventNode() {
                    };
                }
            });
        }

        @Override
        protected void onDispose(Env env) {
            onDisposeCalls++;
        }

    }

    @Test
    public void testNullEventNode() throws IOException {
        Instrument instrument = engine.getInstruments().get("testNullEventNode");
        assureEnabled(instrument);
        TestNullEventNode service = instrument.lookup(TestNullEventNode.class);

        assertEquals(0, service.onNodeCreateCalls);
        assertEquals(0, service.onNodeEnterCalls);

        run("STATEMENT(EXPRESSION)");

        assertTrue(Integer.toString(service.onNodeCreateCalls), service.onNodeCreateCalls >= 2);
        assertEquals(1, service.onNodeEnterCalls);
        service.onNodeCreateCalls = 0;
        service.onNodeEnterCalls = 0;

        run("ROOT(STATEMENT(), EXPRESSION(), STATEMENT(), EXPRESSION())");

        assertTrue(Integer.toString(service.onNodeCreateCalls), service.onNodeCreateCalls >= 4);
        assertEquals(2, service.onNodeEnterCalls);
    }

    @Registration(id = "testNullEventNode", services = {Instrumenter.class, TestNullEventNode.class, Object.class})
    public static class TestNullEventNode extends TruffleInstrument {

        int onNodeCreateCalls = 0;
        int onNodeEnterCalls = 0;

        @Override
        protected void onCreate(final Env env) {
            env.registerService(this);
            env.registerService(env.getInstrumenter());
            env.getInstrumenter().attachExecutionEventFactory(SourceSectionFilter.newBuilder().tagIs(InstrumentationTestLanguage.STATEMENT, InstrumentationTestLanguage.EXPRESSION).build(),
                            new ExecutionEventNodeFactory() {

                                public ExecutionEventNode create(EventContext context) {
                                    onNodeCreateCalls++;
                                    boolean isExpression = context.getInstrumentedSourceSection().getCharacters().toString().startsWith("EXPRESSION");
                                    if (isExpression) {
                                        return null;
                                    } else {
                                        return new ExecutionEventNode() {
                                            @Override
                                            protected void onEnter(VirtualFrame frame) {
                                                onNodeEnterCalls++;
                                                super.onEnter(frame);
                                            }
                                        };
                                    }
                                }
                            });
        }
    }

    @Test
    public void testRootBodies() throws IOException {
        Instrument instrument = engine.getInstruments().get("testRootBodies");
        TestRootBodies service = instrument.lookup(TestRootBodies.class);
        assertEquals("", service.tags.toString());

        run("ROOT(STATEMENT())");

        assertEquals("InRBOutBR", service.tags.toString());
        service.tags.delete(0, service.tags.length());

        run("ROOT(STATEMENT(), ROOT_BODY(EXPRESSION()), EXPRESSION())");

        assertEquals("InRInBOutBOutR", service.tags.toString());
    }

    @Registration(id = "testRootBodies", services = TestRootBodies.class)
    public static class TestRootBodies extends TruffleInstrument {

        final StringBuilder tags = new StringBuilder();

        @Override
        protected void onCreate(Env env) {
            env.registerService(this);
            env.getInstrumenter().attachExecutionEventFactory(SourceSectionFilter.newBuilder().tagIs(StandardTags.RootTag.class, StandardTags.RootBodyTag.class).build(),
                            new ExecutionEventNodeFactory() {

                                @Override
                                public ExecutionEventNode create(EventContext context) {
                                    boolean isRoot = context.hasTag(StandardTags.RootTag.class);
                                    boolean isBody = context.hasTag(StandardTags.RootBodyTag.class);
                                    return new ExecutionEventNode() {
                                        @Override
                                        protected void onEnter(VirtualFrame frame) {
                                            tags.append("In");
                                            if (isRoot) {
                                                tags.append('R');
                                            }
                                            if (isBody) {
                                                tags.append('B');
                                            }
                                        }

                                        @Override
                                        protected void onReturnValue(VirtualFrame frame, Object result) {
                                            tags.append("Out");
                                            if (isBody) {
                                                tags.append('B');
                                            }
                                            if (isRoot) {
                                                tags.append('R');
                                            }
                                        }
                                    };
                                }
                            });
        }
    }

    private void setupEngine(Source initSource, boolean runInitAfterExec) {
        teardown();
        InstrumentationTestLanguage.envConfig.put("initSource", initSource);
        InstrumentationTestLanguage.envConfig.put("runInitAfterExec", runInitAfterExec);
        setup();
    }

    @Test
    public void testAccessInstruments() {
        Instrument instrument = engine.getInstruments().get("testAccessInstruments");
        TestAccessInstruments access = instrument.lookup(TestAccessInstruments.class);

        InstrumentInfo info = access.env.getInstruments().get("testAccessInstruments");
        assertNotNull(info);
        assertEquals("testAccessInstruments", info.getId());
        assertEquals("name", info.getName());
        assertEquals("version", info.getVersion());

        try {
            access.env.lookup(info, TestAccessInstruments.class);
            fail();
        } catch (IllegalArgumentException e) {
            // expected
        }

        TestAccessInstrumentsOther.initializedCount = 0;

        InstrumentInfo other = access.env.getInstruments().get("testAccessInstrumentsOther");
        assertNotNull(other);
        assertEquals("testAccessInstrumentsOther", other.getId());
        assertEquals("otherName", other.getName());
        assertEquals("otherVersion", other.getVersion());

        assertEquals(0, TestAccessInstrumentsOther.initializedCount);

        // invalid service, should not trigger onCreate
        assertNull(access.env.lookup(other, Object.class));
        assertEquals(0, TestAccessInstrumentsOther.initializedCount);

        // valide service, should trigger onCreate
        assertNotNull(access.env.lookup(other, TestAccessInstrumentsOther.class));
        assertEquals(1, TestAccessInstrumentsOther.initializedCount);

        // Check the order of onFinalize and onDispose calls
        StringBuilder onCallsLogger = new StringBuilder();
        instrument.lookup(TestAccessInstruments.class).onCallsLogger = onCallsLogger;
        access.env.lookup(other, TestAccessInstrumentsOther.class).onCallsLogger = onCallsLogger;
        teardown();
        assertEquals("FFDD", onCallsLogger.toString());
    }

    @Test
    public void testAccessLanguages() {
        Instrument instrument = engine.getInstruments().get("testAccessInstruments");
        TestAccessInstruments access = instrument.lookup(TestAccessInstruments.class);

        LanguageInfo info = access.env.getLanguages().get(InstrumentationTestLanguage.ID);
        assertNotNull(info);
        assertEquals(InstrumentationTestLanguage.ID, info.getId());
        assertEquals("2.0", info.getVersion());

        context.enter();
        assertNotNull(access.env.lookup(info, SpecialService.class));
        assertEquals(InstrumentationTestLanguage.FILENAME_EXTENSION, access.env.lookup(info, SpecialService.class).fileExtension());
        context.leave();
    }

    @Registration(id = "testAccessInstruments", name = "name", version = "version", services = TestAccessInstruments.class)
    @SuppressWarnings("hiding")
    public static class TestAccessInstruments extends TruffleInstrument {

        Env env;
        StringBuilder onCallsLogger;

        @Override
        protected void onCreate(final Env env) {
            this.env = env;
            env.registerService(this);
        }

        @Override
        protected void onFinalize(Env env) {
            if (onCallsLogger != null) {
                onCallsLogger.append("F");
            }
        }

        @Override
        protected void onDispose(Env env) {
            if (onCallsLogger != null) {
                onCallsLogger.append("D");
            }
        }

    }

    @Registration(id = "testAccessInstrumentsOther", name = "otherName", version = "otherVersion", services = TestAccessInstrumentsOther.class)
    public static class TestAccessInstrumentsOther extends TruffleInstrument {

        static int initializedCount = 0;
        StringBuilder onCallsLogger;

        @Override
        protected void onCreate(final Env env) {
            env.registerService(this);
            initializedCount++;
        }

        @Override
        protected void onFinalize(Env env) {
            if (onCallsLogger != null) {
                onCallsLogger.append("F");
            }
        }

        @Override
        protected void onDispose(Env env) {
            if (onCallsLogger != null) {
                onCallsLogger.append("D");
            }
        }

    }

    @Test
    public void testAccessInternalInstruments() {
        Instrument instrument = engine.getInstruments().get("testAccessInstruments");
        TestAccessInstruments access = instrument.lookup(TestAccessInstruments.class);
        InstrumentInfo internalInfo = access.env.getInstruments().get("testAccessInternalInstruments");
        assertNotNull(internalInfo);
        TestAccessInternalInstruments internal = access.env.lookup(internalInfo, TestAccessInternalInstruments.class);
        teardown();
        assertEquals("CFD", internal.onCallsLogger.toString());
    }

    @Registration(id = "testAccessInternalInstruments", internal = true, services = TestAccessInternalInstruments.class)
    public static class TestAccessInternalInstruments extends TruffleInstrument {

        StringBuilder onCallsLogger = new StringBuilder();

        @Override
        protected void onCreate(final Env env) {
            onCallsLogger.append("C");
            env.registerService(this);
        }

        @Override
        protected void onFinalize(Env env) {
            onCallsLogger.append("F");
        }

        @Override
        protected void onDispose(Env env) {
            onCallsLogger.append("D");
        }

    }

    @Test
    public void testAccessInstrumentFromLanguage() {
        context.initialize(InstrumentationTestLanguage.ID);
        TruffleLanguage.Env env = InstrumentationTestLanguage.currentEnv();
        LanguageInfo langInfo = env.getInternalLanguages().get(InstrumentationTestLanguage.ID);
        assertNotNull(langInfo);
        assertEquals(InstrumentationTestLanguage.ID, langInfo.getId());
        assertEquals("2.0", langInfo.getVersion());

        InstrumentInfo instrInfo = env.getInstruments().get("testAccessInstruments");
        assertNotNull(instrInfo);
        assertEquals("testAccessInstruments", instrInfo.getId());
        assertEquals("name", instrInfo.getName());
        assertEquals("version", instrInfo.getVersion());

        assertNotNull(env.lookup(instrInfo, TestAccessInstruments.class));
        assertNull(env.lookup(instrInfo, SpecialService.class));

        try {
            // cannot load services from current languages to avoid cycles.
            env.lookup(langInfo, SpecialService.class);
            fail();
        } catch (Exception e1) {
            // expected
        }
    }

    @Test
    public void testLanguageInitializedOrNot() throws Exception {
        Source initSource = Source.create(InstrumentationTestLanguage.ID, "STATEMENT(EXPRESSION, EXPRESSION)");
        setupEngine(initSource, false);

        Instrument instrument = engine.getInstruments().get("testLangInitialized");

        // Events during language initialization phase are included:
        TestLangInitialized.initializationEvents = true;
        TestLangInitialized service = instrument.lookup(TestLangInitialized.class);

        run("LOOP(2, STATEMENT())");
        assertEquals("[FunctionRootNode, false, StatementNode, false, ExpressionNode, false, ExpressionNode, false, FunctionRootNode, true, WhileLoopNode, true, StatementNode, true, StatementNode, true]",
                        service.getEnteredNodes());
    }

    @Test
    public void testLanguageInitializedOnly() throws Exception {
        Source initSource = Source.create(InstrumentationTestLanguage.ID, "STATEMENT(EXPRESSION, EXPRESSION)");
        setupEngine(initSource, false);
        Instrument instrument = engine.getInstruments().get("testLangInitialized");

        // Events during language initialization phase are excluded:
        TestLangInitialized.initializationEvents = false;
        TestLangInitialized service = instrument.lookup(TestLangInitialized.class);
        run("LOOP(2, STATEMENT())");
        assertEquals("[FunctionRootNode, true, WhileLoopNode, true, StatementNode, true, StatementNode, true]", service.getEnteredNodes());
    }

    @Test
    public void testLanguageInitializedOrNotAppend() throws Exception {
        Source initSource = Source.create(InstrumentationTestLanguage.ID, "STATEMENT(EXPRESSION, EXPRESSION)");
        setupEngine(initSource, true);
        Instrument instrument = engine.getInstruments().get("testLangInitialized");

        // Events during language initialization phase are prepended and appended:
        TestLangInitialized.initializationEvents = true;
        TestLangInitialized service = instrument.lookup(TestLangInitialized.class);
        run("LOOP(2, STATEMENT())");
        assertEquals("[FunctionRootNode, false, StatementNode, false, ExpressionNode, false, ExpressionNode, false, " +
                        "FunctionRootNode, true, WhileLoopNode, true, StatementNode, true, StatementNode, true, FunctionRootNode, true, StatementNode, true, ExpressionNode, true, ExpressionNode, true]",
                        service.getEnteredNodes());
    }

    @Test
    public void testLanguageInitializedOnlyAppend() throws Exception {
        Source initSource = Source.create(InstrumentationTestLanguage.ID, "STATEMENT(EXPRESSION, EXPRESSION)");
        setupEngine(initSource, true);
        Instrument instrument = engine.getInstruments().get("testLangInitialized");

        // Events during language initialization phase are excluded,
        // but events from the same nodes used for initialization are appended:
        TestLangInitialized.initializationEvents = false;
        TestLangInitialized service = instrument.lookup(TestLangInitialized.class);
        run("LOOP(2, STATEMENT())");
        assertEquals("[FunctionRootNode, true, WhileLoopNode, true, StatementNode, true, StatementNode, true, FunctionRootNode, true, StatementNode, true, ExpressionNode, true, ExpressionNode, true]",
                        service.getEnteredNodes());
    }

    @Registration(id = "testLangInitialized", services = TestLangInitialized.class)
    public static class TestLangInitialized extends TruffleInstrument implements ExecutionEventListener {

        static boolean initializationEvents;
        private final List<String> enteredNodes = new ArrayList<>();

        @Override
        protected void onCreate(Env env) {
            env.registerService(this);
            env.getInstrumenter().attachExecutionEventListener(SourceSectionFilter.ANY, this);
        }

        @Override
        public void onEnter(EventContext context, VirtualFrame frame) {
            doOnEnter(context);
        }

        @TruffleBoundary
        private void doOnEnter(EventContext context) {
            if (!initializationEvents && !context.isLanguageContextInitialized()) {
                // Skipt language context initialization if initializationEvents is false
                return;
            }
            enteredNodes.add(context.getInstrumentedNode().getClass().getSimpleName());
            enteredNodes.add(Boolean.toString(context.isLanguageContextInitialized()));
        }

        @Override
        public void onReturnValue(EventContext context, VirtualFrame frame, Object result) {
        }

        @Override
        public void onReturnExceptional(EventContext context, VirtualFrame frame, Throwable exception) {
        }

        String getEnteredNodes() {
            return enteredNodes.toString();
        }
    }

    @SuppressWarnings("serial")
    static class TestException extends RuntimeException implements TruffleException {

        final Node location;

        TestException(Node location) {
            super("test");
            this.location = location;
        }

        public Node getLocation() {
            return location;
        }
    }

    @Test
    public void testErrorPropagationCreate() throws Exception {
        Source source = Source.create(InstrumentationTestLanguage.ID, "EXPRESSION");
        accessInstrumenter().attachExecutionEventFactory(SourceSectionFilter.ANY, new ExecutionEventNodeFactory() {
            public ExecutionEventNode create(EventContext c) {
                throw c.createError(new TestException(c.getInstrumentedNode()));
            }
        });
        try {
            context.eval(source);
            fail();
        } catch (PolyglotException e) {
            assertTrue(e.isInternalError());
            assertEquals("java.lang.IllegalStateException: Error propagation is not supported in ExecutionEventNodeFactory.create(EventContext). " +
                            "Errors propagated in this method may result in an AST that never stabilizes. " +
                            "Propagate the error in one of the execution event node events like onEnter, onInputValue, onReturn or onReturnExceptional to resolve this problem.",
                            e.getMessage());
        }
    }

    @Test
    public void testErrorPropagationOnEnter() throws Exception {
        Source source = Source.create(InstrumentationTestLanguage.ID, "EXPRESSION");
        EventBinding<?> b = accessInstrumenter().attachExecutionEventListener(SourceSectionFilter.ANY, new ExecutionEventListener() {
            public void onReturnValue(EventContext c, VirtualFrame frame, Object result) {
            }

            public void onReturnExceptional(EventContext c, VirtualFrame frame, Throwable exception) {
            }

            public void onEnter(EventContext c, VirtualFrame frame) {
                throw c.createError(new TestException(c.getInstrumentedNode()));
            }
        });
        try {
            context.eval(source);
            fail();
        } catch (PolyglotException e) {
            assertFalse(e.toString(), e.isInternalError());
            assertEquals("test", e.getMessage());
            assertEquals(source.getCharacters(), e.getSourceLocation().getCharacters());
        }
        b.dispose();
    }

    @Test
    public void testErrorPropagationOnReturn() throws Exception {
        Source source = Source.create(InstrumentationTestLanguage.ID, "EXPRESSION");
        EventBinding<?> b = accessInstrumenter().attachExecutionEventListener(SourceSectionFilter.ANY, new ExecutionEventListener() {
            public void onReturnValue(EventContext c, VirtualFrame frame, Object result) {
                throw c.createError(new TestException(c.getInstrumentedNode()));
            }

            public void onReturnExceptional(EventContext c, VirtualFrame frame, Throwable exception) {
            }

            public void onEnter(EventContext c, VirtualFrame frame) {
            }
        });
        try {
            context.eval(source);
            fail();
        } catch (PolyglotException e) {
            assertFalse(e.toString(), e.isInternalError());
            assertEquals("test", e.getMessage());
            assertEquals(source.getCharacters(), e.getSourceLocation().getCharacters());
        }
        b.dispose();
    }

    @Test
    public void testErrorPropagationOnReturnExceptional() throws Exception {
        Source source = Source.create(InstrumentationTestLanguage.ID, "EXPRESSION(THROW(test, test))");
        EventBinding<?> b = accessInstrumenter().attachExecutionEventListener(SourceSectionFilter.ANY, new ExecutionEventListener() {
            public void onReturnValue(EventContext c, VirtualFrame frame, Object result) {
            }

            public void onReturnExceptional(EventContext c, VirtualFrame frame, Throwable exception) {
                throw c.createError(new TestException(c.getInstrumentedNode()));
            }

            public void onEnter(EventContext c, VirtualFrame frame) {
            }
        });
        try {
            context.eval(source);
            fail();
        } catch (PolyglotException e) {
            assertFalse(e.toString(), e.isInternalError());
            assertEquals("test", e.getMessage());
            assertEquals(source.getCharacters(), e.getSourceLocation().getCharacters());
        }
        b.dispose();
    }

    @Test
    public void testErrorPropagationOnInputValue() throws Exception {
        Source source = Source.create(InstrumentationTestLanguage.ID, "EXPRESSION(EXPRESSION)");
        EventBinding<?> b = accessInstrumenter().attachExecutionEventFactory(SourceSectionFilter.ANY, SourceSectionFilter.ANY, (c) -> {
            return new ExecutionEventNode() {
                @Override
                protected void onInputValue(VirtualFrame frame, EventContext inputContext, int inputIndex, Object inputValue) {
                    throw c.createError(new TestException(c.getInstrumentedNode()));
                }
            };
        });
        try {
            context.eval(source);
            fail();
        } catch (PolyglotException e) {
            assertFalse(e.toString(), e.isInternalError());
            assertEquals("test", e.getMessage());
            assertEquals(source.getCharacters(), e.getSourceLocation().getCharacters());
        }
        b.dispose();
    }

    @Test
    public void testErrorPropagationOnUnwind() throws Exception {
        Source source = Source.create(InstrumentationTestLanguage.ID, "EXPRESSION");
        EventBinding<?> b = accessInstrumenter().attachExecutionEventFactory(SourceSectionFilter.ANY, (c) -> {
            return new ExecutionEventNode() {

                @Override
                public void onReturnValue(VirtualFrame frame, Object result) {
                    throw c.createUnwind("test");
                }

                @Override
                @TruffleBoundary
                protected Object onUnwind(VirtualFrame frame, Object info) {
                    assertEquals("test", info);
                    throw c.createError(new TestException(c.getInstrumentedNode()));
                }
            };
        });
        try {
            context.eval(source);
            fail();
        } catch (PolyglotException e) {
            assertFalse(e.toString(), e.isInternalError());
            assertEquals("test", e.getMessage());
            assertEquals(source.getCharacters(), e.getSourceLocation().getCharacters());
        }
        b.dispose();
    }

    @Test
    public void testErrorPropagationOnReturnSuppressed() throws Exception {
        Source source = Source.create(InstrumentationTestLanguage.ID, "EXPRESSION");
        EventBinding<?> b0 = accessInstrumenter().attachExecutionEventFactory(SourceSectionFilter.ANY, (c) -> {
            return new ExecutionEventNode() {

                @Override
                public void onReturnValue(VirtualFrame frame, Object result) {
                    throw c.createError(new TestException(c.getInstrumentedNode()));
                }
            };
        });

        EventBinding<?> b1 = accessInstrumenter().attachExecutionEventFactory(SourceSectionFilter.ANY, (c) -> {
            return new ExecutionEventNode() {

                @Override
                public void onReturnValue(VirtualFrame frame, Object result) {
                    throw c.createError(new TestException(c.getInstrumentedNode()));
                }
            };
        });
        try {
            context.eval(source);
            fail();
        } catch (PolyglotException e) {
            assertFalse(e.toString(), e.isInternalError());
            assertEquals("test", e.getMessage());
            assertEquals(source.getCharacters(), e.getSourceLocation().getCharacters());
            e.getSuppressed();
        }
        b0.dispose();
        b1.dispose();
    }

    private Instrumenter accessInstrumenter() {
        return engine.getInstruments().get("accessInstrumenter").lookup(AccessInstrumenter.class).getLastEnv().getInstrumenter();
    }

    @Registration(id = "accessInstrumenter", services = {AccessInstrumenter.class})
    public static class AccessInstrumenter extends TruffleInstrument {

        private Env lastEnv;

        @Override
        protected void onCreate(Env env) {
            this.lastEnv = env;
            this.lastEnv.registerService(this);
        }

        public Env getLastEnv() {
            return lastEnv;
        }

    }

    @Test
    public void testAllocation() throws Exception {
        Instrument instrument = engine.getInstruments().get("testAllocation");
        assureEnabled(instrument);
        TestAllocation allocation = instrument.lookup(TestAllocation.class);
        run("LOOP(3, VARIABLE(a, 10))");
        assertEquals("[W 4 null, A 4 10, W 4 null, A 4 10, W 4 null, A 4 10]", allocation.getAllocations());

    }

    @Registration(id = "testAllocation", services = {TestAllocation.class, Object.class})
    public static class TestAllocation extends TruffleInstrument implements AllocationListener {

        private final List<String> allocations = new ArrayList<>();

        @Override
        protected void onCreate(Env env) {
            env.registerService(this);
            LanguageInfo testLanguage = env.getLanguages().get(InstrumentationTestLanguage.ID);
            env.getInstrumenter().attachAllocationListener(AllocationEventFilter.newBuilder().languages(testLanguage).build(), this);
        }

        String getAllocations() {
            return allocations.toString();
        }

        @Override
        @TruffleBoundary
        public void onEnter(AllocationEvent event) {
            allocations.add("W " + event.getNewSize() + " " + event.getValue());
        }

        @Override
        @TruffleBoundary
        public void onReturnValue(AllocationEvent event) {
            allocations.add("A " + event.getNewSize() + " " + event.getValue());
        }
    }

    @Test
    public void testPolyglotBindings() throws Exception {
        Instrument bindingsTestInstrument = context.getEngine().getInstruments().get(BindingsTestInstrument.ID);
        Object bindingsObject = bindingsTestInstrument.lookup(Supplier.class).get();
        InteropLibrary interop = InteropLibrary.getFactory().getUncached();

        assertTrue(interop.hasMembers(bindingsObject));
        assertFalse(interop.isNull(bindingsObject));
        assertFalse(interop.isExecutable(bindingsObject));
        assertFalse(interop.isInstantiable(bindingsObject));

        final String m1 = "member1";
        final String m2 = "member2";

        // Bindings are empty initially
        assertFalse(interop.isMemberExisting(bindingsObject, m1));
        assertFalse(interop.isMemberExisting(bindingsObject, m2));
        assertFalse(context.getPolyglotBindings().hasMember(m1));
        assertFalse(context.getPolyglotBindings().hasMember(m2));

        // Value set by Context can be read by instrument
        context.getPolyglotBindings().putMember(m1, 10);
        assertTrue(interop.isMemberExisting(bindingsObject, m1));
        assertEquals(10, interop.readMember(bindingsObject, m1));

        // Value set by instrument can be read by Context
        interop.writeMember(bindingsObject, m1, 11);
        interop.writeMember(bindingsObject, m2, 20);
        assertEquals(11, context.getPolyglotBindings().getMember(m1).asInt());
        assertEquals(20, context.getPolyglotBindings().getMember(m2).asInt());

        // Remove works from both sides
        interop.removeMember(bindingsObject, m1);
        context.getPolyglotBindings().removeMember(m2);
        assertFalse(interop.isMemberExisting(bindingsObject, m1));
        assertFalse(interop.isMemberExisting(bindingsObject, m2));
        assertFalse(context.getPolyglotBindings().hasMember(m1));
        assertFalse(context.getPolyglotBindings().hasMember(m2));
    }

    @TruffleInstrument.Registration(id = BindingsTestInstrument.ID, services = Supplier.class)
    public static class BindingsTestInstrument extends TruffleInstrument {

        static final String ID = "bindings-test-instrument";

        @Override
        protected void onCreate(Env env) {
            env.registerService(new Supplier<Object>() {
                @Override
                public Object get() {
                    return env.getPolyglotBindings();
                }
            });
        }

    }

    private static final class MyKillException extends ThreadDeath {
        static final long serialVersionUID = 1;
    }
}
