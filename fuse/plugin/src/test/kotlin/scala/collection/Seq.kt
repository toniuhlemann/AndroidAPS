package scala.collection

/**
 * TEST-ONLY PLACEHOLDER - there is no Scala in this project.
 *
 * Mockito 5.24 checks for Scala varargs every time it matches a stubbed call whose raw argument count differs
 * from the processed one - and a Kotlin `suspend` function is exactly that (the Continuation is cut off). The
 * check is `Class.forName("scala.collection.Seq")`, without a cache: when the class is missing, every matched call
 * searches the whole test classpath on disk. In v4 the AAPS reads of the FUSE cycle are suspend, and the runner
 * rigs make hundreds of thousands of them - the rig tests went from minutes to hours.
 *
 * With this interface on the test classpath the lookup succeeds once and is then answered by the JVM's class
 * cache. Behaviour does not change: the Scala path only applies when the raw argument count equals the matcher
 * count, which for a suspend function it never does (one Continuation more).
 */
internal interface Seq
