package lila.studySearch

import akka.actor._
import com.typesafe.config.Config
import scala.concurrent.duration._

import lila.common.paginator._
import lila.hub.LateMultiThrottler
import lila.search._
import lila.study.Study
import lila.user.User

final class Env(
    config: Config,
    studyEnv: lila.study.Env,
    makeClient: Index => ESClient,
    system: ActorSystem) {

  private val IndexName = config getString "index"
  private val MaxPerPage = config getInt "paginator.max_per_page"

  private val client = makeClient(Index(IndexName))

  private val indexThrottler = system.actorOf(Props(new LateMultiThrottler(
    executionTimeout = 3.seconds.some,
    logger = logger)))

  val api = new StudySearchApi(
    client = client,
    indexThrottler = indexThrottler,
    studyEnv.studyRepo,
    studyEnv.chapterRepo)

  def apply(me: Option[User])(text: String, page: Int) =
    Paginator[Study.WithChaptersAndLiked](
      adapter = new AdapterLike[Study] {
      def query = Query(text, me.map(_.id))
      def nbResults = api count query
      def slice(offset: Int, length: Int) = api.search(query, From(offset), Size(length))
    } mapFutureList studyEnv.pager.withChapters mapFutureList studyEnv.pager.withLiking(me),
      currentPage = page,
      maxPerPage = MaxPerPage)

  def cli = new lila.common.Cli {
    def process = {
      case "study" :: "search" :: "reset" :: Nil => api.reset inject "done"
    }
  }

  system.lilaBus.subscribe(system.actorOf(Props(new Actor {
    import lila.study.actorApi._
    def receive = {
      case SaveStudy(study) => api store study
      case RemoveStudy(id)  => client deleteById Id(id.value)
    }
  })), 'study)
}

object Env {

  lazy val current = "studySearch" boot new Env(
    config = lila.common.PlayApp loadConfig "studySearch",
    studyEnv = lila.study.Env.current,
    makeClient = lila.search.Env.current.makeClient,
    system = lila.common.PlayApp.system)
}
