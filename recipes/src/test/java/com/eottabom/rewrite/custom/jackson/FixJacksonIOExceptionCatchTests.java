package com.eottabom.rewrite.custom.jackson;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class FixJacksonIOExceptionCatchTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new FixJacksonIOExceptionCatch())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package tools.jackson.core; public class JacksonException extends RuntimeException {}",
						"package tools.jackson.databind; public class ObjectMapper { public <T> T readValue(String s, Class<T> c) { return null; } }"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void rewrites(String scenario, String before, String after) {
		rewriteRun((after != null) ? java(before, after) : java(before));
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of(
				"adds i o exception when try body throws it",
				"""
				import java.nio.file.Files;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object load(Path path) {
				        try {
				            return objectMapper.readValue(Files.readString(path), Object.class);
				        } catch (JacksonException e) {
				            throw new IllegalStateException(e);
				        }
				    }
				}
				""",
				"""
				import java.io.IOException;
				import java.nio.file.Files;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object load(Path path) {
				        try {
				            return objectMapper.readValue(Files.readString(path), Object.class);
				        } catch (JacksonException | IOException e) {
				            throw new IllegalStateException(e);
				        }
				    }
				}
				"""
			),
			Arguments.of(
				"leaves alone when no i o exception or already caught",
				"""
				import java.io.IOException;
				import java.nio.file.Files;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object parse(String s) {
				        try {
				            return objectMapper.readValue(s, Object.class);
				        } catch (JacksonException e) {
				            return null;
				        }
				    }
				    Object load(Path path) {
				        try {
				            return objectMapper.readValue(Files.readString(path), Object.class);
				        } catch (JacksonException | IOException e) {
				            return null;
				        }
				    }
				}
				""",
				null
			),
			Arguments.of(
				"removes i o exception when never thrown",
				"""
				import java.io.IOException;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Parser {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object parse(String s) {
				        try {
				            return objectMapper.readValue(s, Object.class);
				        } catch (JacksonException | IOException e) {
				            return null;
				        }
				    }
				}
				""",
				"""
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Parser {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object parse(String s) {
				        try {
				            return objectMapper.readValue(s, Object.class);
				        } catch (JacksonException e) {
				            return null;
				        }
				    }
				}
				"""
			),
			Arguments.of(
				"adds to the jackson catch among several catches, even for constructors",
				"""
				import java.io.FileInputStream;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object load(Path path) {
				        try {
				            Runnable noop = () -> {};
				            return objectMapper.readValue(new FileInputStream(path.toFile()).toString(), Object.class);
				        } catch (IllegalArgumentException e) {
				            return null;
				        } catch (JacksonException | IllegalStateException e) {
				            return null;
				        } catch (JacksonException e) {
				            return null;
				        }
				    }
				}
				""",
				"""
				import java.io.FileInputStream;
				import java.io.IOException;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object load(Path path) {
				        try {
				            Runnable noop = () -> {};
				            return objectMapper.readValue(new FileInputStream(path.toFile()).toString(), Object.class);
				        } catch (IllegalArgumentException e) {
				            return null;
				        } catch (JacksonException | IllegalStateException e) {
				            return null;
				        } catch (JacksonException | IOException e) {
				            return null;
				        }
				    }
				}
				"""
			),
			Arguments.of(
				"leaves try without a jackson catch",
				"""
				import java.nio.file.Files;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;

				class Loader {
				    JacksonException last;
				    String load(Path path) {
				        try {
				            return Files.readString(path);
				        } catch (RuntimeException e) {
				            return null;
				        }
				    }
				}
				""",
				null
			),
			Arguments.of(
				"removes i o exception from a longer multi catch and skips others",
				"""
				import java.io.IOException;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Parser {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object parse(String s) throws InterruptedException {
				        try {
				            Thread.sleep(1);
				            return objectMapper.readValue(s, Object.class);
				        } catch (IllegalStateException | IOException e) {
				            return null;
				        } catch (JacksonException | IllegalArgumentException e) {
				            return null;
				        } catch (JacksonException | UnsupportedOperationException | IOException e) {
				            return null;
				        }
				    }
				}
				""",
				"""
				import java.io.IOException;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Parser {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object parse(String s) throws InterruptedException {
				        try {
				            Thread.sleep(1);
				            return objectMapper.readValue(s, Object.class);
				        } catch (IllegalStateException | IOException e) {
				            return null;
				        } catch (JacksonException | IllegalArgumentException e) {
				            return null;
				        } catch (JacksonException | UnsupportedOperationException e) {
				            return null;
				        }
				    }
				}
				"""
			)
		);
	}
	// @formatter:on

	@Test
	void keepsIOExceptionForExplicitThrow() {
		rewriteRun(java("""
				import java.io.IOException;
				import tools.jackson.core.JacksonException;

				class Loader {
				    void load(boolean broken) {
				        try {
				            if (broken) {
				                throw new IOException("broken");
				            }
				        } catch (JacksonException | IOException e) {
				            throw new IllegalStateException(e);
				        }
				    }
				}
				"""));
	}

	@Test
	void removesIOExceptionHandledByNestedTryOrThrownInsideAnonymousClass() {
		rewriteRun(java("""
				import java.io.IOException;
				import java.nio.file.Files;
				import java.nio.file.Path;
				import java.util.concurrent.Callable;
				import tools.jackson.core.JacksonException;

				class Loader {
				    Object load(Path path) {
				        try {
				            try {
				                Files.readString(path);
				            } catch (IOException ignored) {
				            }
				            Callable<String> read = new Callable<>() {
				                @Override
				                public String call() throws IOException {
				                    return Files.readString(path);
				                }
				            };
				            class Local {
				                String read() throws IOException {
				                    return Files.readString(path);
				                }
				            }
				            return read;
				        } catch (JacksonException | IOException e) {
				            throw new IllegalStateException(e);
				        }
				    }
				}
				""", """
				import java.io.IOException;
				import java.nio.file.Files;
				import java.nio.file.Path;
				import java.util.concurrent.Callable;
				import tools.jackson.core.JacksonException;

				class Loader {
				    Object load(Path path) {
				        try {
				            try {
				                Files.readString(path);
				            } catch (IOException ignored) {
				            }
				            Callable<String> read = new Callable<>() {
				                @Override
				                public String call() throws IOException {
				                    return Files.readString(path);
				                }
				            };
				            class Local {
				                String read() throws IOException {
				                    return Files.readString(path);
				                }
				            }
				            return read;
				        } catch (JacksonException e) {
				            throw new IllegalStateException(e);
				        }
				    }
				}
				"""));
	}

	@Test
	void ignoresCallsWithoutTypeInformation() {
		rewriteRun((spec) -> spec.typeValidationOptions(TypeValidation.none()), java("""
				import tools.jackson.core.JacksonException;

				class Unknown {
				    Object call() {
				        try {
				            return missing();
				        } catch (JacksonException e) {
				            return null;
				        }
				    }
				}
				"""));
	}

	@Test
	void ignoresStaleThrowsOfMigratedJacksonMethods() {
		// upstream 이 이름만 바꾼 상태라 tools.jackson 메서드에 Jackson 2 의 throws IOException 이 남아 있다
		rewriteRun((spec) -> spec.parser(JavaParser.fromJavaVersion()
			.dependsOn("package tools.jackson.core; public class JacksonException extends RuntimeException {}",
					"package tools.jackson.databind; public class ObjectMapper { public String writeValueAsString(Object o) throws java.io.IOException { return null; } }")),
				java("""
						import java.io.IOException;
						import tools.jackson.core.JacksonException;
						import tools.jackson.databind.ObjectMapper;

						class Writer {
						    ObjectMapper objectMapper = new ObjectMapper();
						    String write(Object o) {
						        try {
						            return objectMapper.writeValueAsString(o);
						        } catch (JacksonException | IOException e) {
						            return null;
						        }
						    }
						}
						""", """
						import tools.jackson.core.JacksonException;
						import tools.jackson.databind.ObjectMapper;

						class Writer {
						    ObjectMapper objectMapper = new ObjectMapper();
						    String write(Object o) {
						        try {
						            return objectMapper.writeValueAsString(o);
						        } catch (JacksonException e) {
						            return null;
						        }
						    }
						}
						"""));
	}

	@Test
	void keepsIOExceptionThrownByTryResource() {
		rewriteRun(java("""
				import java.io.FileReader;
				import java.io.IOException;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object load() {
				        try (FileReader reader = new FileReader("test.json")) {
				            return objectMapper.readValue(reader.toString(), Object.class);
				        } catch (JacksonException | IOException e) {
				            return null;
				        }
				    }
				}
				"""));
	}

	@Test
	void keepsIOExceptionFromInheritedCloseOnExistingResource() {
		rewriteRun(java("""
				import java.io.Closeable;
				import java.io.IOException;
				import tools.jackson.core.JacksonException;

				class Loader {
				    interface Resource extends Closeable {}
				    void load(Resource resource) {
				        try (resource) {
				            throw new JacksonException();
				        } catch (JacksonException | IOException e) {
				        }
				    }
				}
				"""));
	}

	@Test
	void addsIOExceptionForImplicitClose() {
		rewriteRun(java("""
				import java.io.Closeable;
				import java.io.IOException;
				import tools.jackson.core.JacksonException;

				class Resource implements Closeable {
				    public void close() throws IOException {}
				    void load() throws IOException {
				        try (Resource resource = new Resource()) {
				            throw new JacksonException();
				        } catch (JacksonException e) {
				        }
				    }
				}
				""", """
				import java.io.Closeable;
				import java.io.IOException;
				import tools.jackson.core.JacksonException;

				class Resource implements Closeable {
				    public void close() throws IOException {}
				    void load() throws IOException {
				        try (Resource resource = new Resource()) {
				            throw new JacksonException();
				        } catch (JacksonException | IOException e) {
				        }
				    }
				}
				"""));
	}

	@Test
	void respectsCloseOverrideWithoutCheckedExceptions() {
		rewriteRun(java("""
				import java.io.Closeable;
				import tools.jackson.core.JacksonException;

				class Resource implements Closeable {
				    public void close() {}
				    void load() {
				        try (Resource resource = new Resource()) {
				            throw new JacksonException();
				        } catch (JacksonException e) {
				        }
				    }
				}
				"""));
	}

}
