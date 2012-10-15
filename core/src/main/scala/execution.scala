package dispatch

import com.ning.http.client.{
  AsyncHttpClient, RequestBuilder, Request, Response, AsyncHandler,
  AsyncHttpClientConfig
}
import org.jboss.netty.util.{Timer,HashedWheelTimer}
import java.util.{concurrent => juc}

/** Http executor with defaults */
case class Http(
  client: AsyncHttpClient = Defaults.client,
  timeout: Duration = Defaults.timeout,
  promiseExecutor: juc.Executor = Defaults.promiseExecutor,
  timer: Timer = Defaults.timer
) extends Executor {
  /** Convenience method for an Executor with the given timeout */
  def waiting(t: Duration) = copy(timeout=t)

  /** Convenience method for an executor with a fixed thread pool of
      the given size */
  def threads(promiseThreadPoolSize: Int) =
    copy(promiseExecutor = DaemonThreadPool(promiseThreadPoolSize))
}

/** Singleton default Http executor, can be used directly or altered
 *  with its case-class `copy` */
object Http extends Http(
  Defaults.client,
  Defaults.timeout,
  Defaults.promiseExecutor,
  Defaults.timer
)

private [dispatch] object Defaults {
  def client = new AsyncHttpClient
  def timeout = Duration.Zero
  def promiseExecutor = DaemonThreadPool(256)
  def timer = new HashedWheelTimer
}

trait Executor { self =>
  def promiseExecutor: juc.Executor
  def timer: Timer
  def client: AsyncHttpClient
  /** Timeout for promises made by this HTTP Executor */
  def timeout: Duration

  def apply(builder: RequestBuilder): Promise[Response] =
    apply(builder.build() -> new FunctionHandler(identity))

  def apply[T](pair: (Request, AsyncHandler[T])): Promise[T] =
    apply(pair._1, pair._2)

  def apply[T](request: Request, handler: AsyncHandler[T]): Promise[T] =
    new ListenableFuturePromise(
      client.executeRequest(request, handler),
      promiseExecutor,
      timeout
    )

  def shutdown() {
    client.close()
    timer.stop()
    promiseExecutor match {
      case service: juc.ExecutorService => service.shutdown()
      case _ => ()
    }
  }
}

object DaemonThreadPool {
  /** produces daemon threads that won't block JVM shutdown */
  val factory = new juc.ThreadFactory {
    def newThread(r: Runnable): Thread ={
      val thread = new Thread
      thread.setDaemon(true)
      thread
    }
  }
  def apply(threadPoolSize: Int) =
    juc.Executors.newFixedThreadPool(threadPoolSize, factory)
}
