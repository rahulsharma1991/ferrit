package org.ferrit.server

import akka.actor.{Actor, ActorRef}
import akka.testkit.TestActorRef
import org.joda.time.DateTime
import org.scalatest.FlatSpec;
import org.scalatest.matchers.ShouldMatchers
import org.scalamock.scalatest.MockFactory
import play.api.libs.json._
import scala.util.{Success, Failure}
import spray.testkit.ScalatestRouteTest
import spray.http._
import spray.http.ContentTypes.`application/json`
import org.ferrit.core.crawler.{CrawlerManager, CrawlLog, CrawlConfig}
import org.ferrit.core.crawler.{CrawlerManager, CrawlLog}
import org.ferrit.core.filter.PriorityRejectUriFilter
import org.ferrit.core.filter.PriorityRejectUriFilter.Accept
import org.ferrit.core.json.PlayJsonImplicits._
import org.ferrit.server.json.PlayJsonImplicits._
import org.ferrit.core.model.{Crawler, CrawlJob, DocumentMetaData, FetchLogEntry}
import org.ferrit.dao.{CrawlerDAO, CrawlJobDAO, FetchLogEntryDAO, DocumentMetaDataDAO, DocumentDAO}
import org.ferrit.dao.{DAOFactory, Journal}
import org.ferrit.core.uri.CrawlUri
import org.ferrit.core.util.Media
import org.ferrit.server.json.{ErrorMessage, Message}


class TestRestService extends FlatSpec with MockFactory with ScalatestRouteTest with ShouldMatchers {
  
  // See: Spray's RequestBuildingExamplesSpec for more ...
  
  trait RouteTest extends RestServiceRoutes {
    override def actorRefFactory = system
    override val ferrit: ActorRef = TestActorRef[Ferrit]
    override val crawlerManager: ActorRef = TestActorRef[Placeholder]
    override val logger: ActorRef = TestActorRef[CrawlLog]
    override def createJournal: ActorRef = TestActorRef[Placeholder]
    override val fleDao: FetchLogEntryDAO = stub[FetchLogEntryDAO]
    override val crawlJobDao: CrawlJobDAO = stub[CrawlJobDAO]
    override val crawlerDao: CrawlerDAO = stub[CrawlerDAO]
    override val docMetaDao: DocumentMetaDataDAO = stub[DocumentMetaDataDAO]
  }

  trait Data {    
    
    val crawlerId = "good-crawler-id"
    val badCrawlerId = "bad-crawler-id"
    val jobId = "good-job-id"
    val badJobId = "bad-job-id"
    
    val config = makeConfig(crawlerId, "http://www.website.com")
    val configs = Seq(config)
    
    val crawlers = configs.map(c => Crawler(c.id, c))
    val crawler = crawlers(0)

    val jobs = Seq(
      makeJob(crawlerId, "1234", new DateTime()),
      makeJob(crawlerId, "5678", new DateTime())
    )
    val job = jobs(0)

    private val uris = Seq(
      "http://www.website.com", 
      "http://www.website.com/products",
      "http://www.website.com/contact"
    )

    val docs = uris.map(uri => makeDoc(crawlerId, jobId, uri))
    val doc = docs(0)

    val fetches = uris.map(uri => makeFetch(crawlerId, jobId, uri))
    val fetch = fetches(0)

    val configJson = Json.stringify(Json.toJson(config))
    val configsJson = Json.stringify(Json.toJson(configs))
    val jobJson = Json.stringify(Json.toJson(job))
    val jobsJson = Json.stringify(Json.toJson(jobs))
    val docJson = Json.stringify(Json.toJson(doc))
    val docsJson = Json.stringify(Json.toJson(docs))
    val fetchJson = Json.stringify(Json.toJson(fetch))
    val fetchesJson = Json.stringify(Json.toJson(fetches))

    val badCrawlerJson = """{"statusCode":404,"message":"No crawler found with identifier [bad-crawler-id]"}"""
    val badJobJson = """{"statusCode":404,"message":"No crawl job found with identifier [bad-job-id]"}"""
    val badUnmarshallMsg = """{"statusCode":400,"message":"The request entity could not be unmarshalled."}"""
    val failedTestMsg = "[http://othersite.com] is rejected because no accept pattern accepted it"
  }


  it should "200 GET /crawlers with crawl configs array" in new RouteTest with Data {
    
    (crawlerDao.findAll _).when().returns(crawlers)
    Get("/crawlers") ~> routes ~> check {
      responseAs[String] should equal(configsJson)
    }
  }

  it should "200 GET /crawlers/{crawlerId} with single config" in new RouteTest with Data {

    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    Get(s"/crawlers/$crawlerId") ~> routes ~> check {
      responseAs[String] should equal(configJson)
    }
  }

  it should "404 GET /crawlers/{crawlerId} for non-existent crawler" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(badCrawlerId).returns(None)
    Get(s"/crawlers/$badCrawlerId") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badCrawlerJson)
    }
  }

  it should "200 GET /crawlers/{crawlerId}/jobs with crawler jobs" in new RouteTest with Data {
      
    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    (crawlJobDao.find(_:String)).when(crawlerId).returns(jobs)
    Get(s"/crawlers/$crawlerId/jobs") ~> routes ~> check {
      responseAs[String] should equal (jobsJson)
    }
  }

  it should "404 GET /crawlers/{crawlerId}/jobs for non-existent crawler" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(badCrawlerId).returns(None)
    Get(s"/crawlers/$badCrawlerId/jobs") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badCrawlerJson)
    }
  }

  it should "200 GET /crawlers/{crawlerId}/jobs/{jobId} with job details" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    (crawlJobDao.find(_:String, _:String)).when(crawlerId, jobId).returns(Some(job))
    Get(s"/crawlers/$crawlerId/jobs/$jobId") ~> routes ~> check {
      responseAs[String] should equal (jobJson)
    }
  }

  it should "404 GET /crawlers/{crawlerId}/jobs/{jobId} for non-existent crawler" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(badCrawlerId).returns(None)
    Get(s"/crawlers/$badCrawlerId/jobs/$jobId") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badCrawlerJson)
    }
  }

  it should "404 GET /crawlers/{crawlerId}/jobs/{jobId} for non-existent job" in new RouteTest with Data {
   
    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    (crawlJobDao.find(_:String, _:String)).when(crawlerId, badJobId).returns(None)
    Get(s"/crawlers/$crawlerId/jobs/$badJobId") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badJobJson)
    }
  }

  it should "200 GET /crawlers/{crawlerId}/jobs/{jobId}/assets with asset details" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    (crawlJobDao.find(_:String, _:String)).when(crawlerId, jobId).returns(Some(job))
    (docMetaDao.find(_:String)).when(jobId).returns(docs)
    Get(s"/crawlers/$crawlerId/jobs/$jobId/assets") ~> routes ~> check {
      responseAs[String] should equal (docsJson)
    }
  }

  it should "404 GET /crawlers/{crawlerId}/jobs/{jobId}/assets for non-existent crawler" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(badCrawlerId).returns(None)
    Get(s"/crawlers/$badCrawlerId/jobs/$jobId/assets") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badCrawlerJson)
    }
  }

  it should "404 GET /crawlers/{crawlerId}/jobs/{jobId}/assets for non-existent job" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    (crawlJobDao.find(_:String, _:String)).when(crawlerId, badJobId).returns(None)
    Get(s"/crawlers/$crawlerId/jobs/$badJobId/assets") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badJobJson)
    }
  }

  it should "200 GET /crawlers/{crawlerId}/jobs/{jobId}/fetches with fetch list" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    (crawlJobDao.find(_:String, _:String)).when(crawlerId, jobId).returns(Some(job))
    (fleDao.find(_:String)).when(jobId).returns(fetches)
    Get(s"/crawlers/$crawlerId/jobs/$jobId/fetches") ~> routes ~> check {
      responseAs[String] should equal (fetchesJson)
    }
  }

  it should "404 GET /crawlers/{crawlerId}/jobs/{jobId}/fetches for non-existent crawler" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(badCrawlerId).returns(None)
    Get(s"/crawlers/$badCrawlerId/jobs/$jobId/fetches") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badCrawlerJson)
    }
  }

  it should "404 GET /crawlers/{crawlerId}/jobs/{jobId}/fetches for non-existent job" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    (crawlJobDao.find(_:String, _:String)).when(crawlerId, badJobId).returns(None)
    Get(s"/crawlers/$crawlerId/jobs/$badJobId/fetches") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badJobJson)
    }
  }

  it should "201 POST /crawlers with valid crawl config" in new RouteTest with Data {

    (crawlerDao.insert(_:Crawler)).when(*)
    Post(s"/crawlers", HttpEntity(`application/json`, configJson)) ~> routes ~> check {
      status should equal (StatusCodes.Created)
    }

  }

  it should "400 POST /crawlers when crawl config malformed" in new RouteTest with Data {

    val badConfigWithNoName = """{"id":"new", "name":""}"""
    (crawlerDao.insert(_:Crawler)).when(*)
    Post(s"/crawlers", HttpEntity(`application/json`, badConfigWithNoName)) ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.BadRequest)
      responseAs[String] should equal (badUnmarshallMsg)
    }

  }

  it should "400 POST /crawlers when tests in the crawl config fail" in new RouteTest with Data {

    val configFailed = config.copy(tests = Some(Seq("should accept: http://othersite.com")))
    val configFailedJson = Json.stringify(Json.toJson(configFailed))
    Post(s"/crawlers", HttpEntity(`application/json`, configFailedJson)) ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.BadRequest)
      responseAs[String] should include (failedTestMsg)
    }

  }

  it should "200 POST /crawl-config-test" in new RouteTest with Data {
    Post(s"/crawl-config-test", HttpEntity(`application/json`, configJson)) ~> routes ~> check {
      status should equal (StatusCodes.OK)
    }
  }

  it should "400 POST /crawl-config-test when tests in the crawl config fail" in new RouteTest with Data {

    val configFailed = config.copy(tests = Some(Seq("should accept: http://othersite.com")))
    val configFailedJson = Json.stringify(Json.toJson(configFailed))
    Post(s"/crawl-config-test", HttpEntity(`application/json`, configFailedJson)) ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.BadRequest)
      responseAs[String] should include (failedTestMsg)
    }

  }

  it should "201 PUT /crawlers/{crawlerId} with valid crawl config" in new RouteTest with Data {

    applyStubForPutHack(crawlerDao, crawlerId, crawler)
    (crawlerDao.insert(_:Crawler)).when(*)
    Put(s"/crawlers/$crawlerId", HttpEntity(`application/json`, configJson)) ~> routes ~> check {
      status should equal (StatusCodes.Created)
    }

  }

  it should "404 PUT /crawlers/{crawlerId} for non-existent crawler" in new RouteTest with Data {
    
    (crawlerDao.find(_:String)).when(badCrawlerId).returns(None)
    Put(s"/crawlers/$badCrawlerId", HttpEntity(`application/json`, configJson)) ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal(badCrawlerJson)
    }
  } 

  it should "400 PUT /crawlers/{crawlerId} when tests in the crawl config fail" in new RouteTest with Data {

    val configFailed = config.copy(tests = Some(Seq("should accept: http://othersite.com")))
    val configFailedJson = Json.stringify(Json.toJson(configFailed))
    applyStubForPutHack(crawlerDao, crawlerId, crawler)

    Put(s"/crawlers/$crawlerId", HttpEntity(`application/json`, configFailedJson)) ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.BadRequest)
      responseAs[String] should include (failedTestMsg)
    }

  }

  it should "204 DELETE /crawlers/{crawlerId}" in new RouteTest with Data {

    (crawlerDao.find(_:String)).when(crawlerId).returns(Some(crawler))
    (crawlerDao.delete(_:String)).when(crawlerId)
    Delete(s"/crawlers/$crawlerId") ~> routes ~> check {
      status should equal (StatusCodes.NoContent)
    }
  }

  it should "404 DELETE /crawlers/{crawlerId} for non-existent crawler" in new RouteTest with Data {
    (crawlerDao.find(_:String)).when(badCrawlerId).returns(None)
    Delete(s"/crawlers/$badCrawlerId") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.NotFound)
      responseAs[String] should equal (badCrawlerJson)
    }
  }

  it should "200 GET /jobs" in new RouteTest with Data {
    (crawlJobDao.find(_:DateTime)).when(*).returns(jobs)
    Get("/jobs") ~> routes ~> check {
      status should equal (StatusCodes.OK)
      responseAs[String] should equal (jobsJson)
    }
  }

  it should "200 GET /jobs and default to today's jobs" in new RouteTest with Data {
    val today = new DateTime().withTimeAtStartOfDay
    (crawlJobDao.find(_:DateTime)).when(today).returns(jobs)
    Get("/jobs") ~> routes ~> check {
      status should equal (StatusCodes.OK)
      responseAs[String] should equal (jobsJson)
    }
  }

  it should "400 GET /jobs when date param is invalid" in new RouteTest with Data {
    Get("/jobs?date=BAD-DATE") ~> sealRoute(routes) ~> check {
      status should equal (StatusCodes.BadRequest)
      responseAs[String] should equal (
        """{"statusCode":400,"message":"Parameter [date] has invalid value [BAD-DATE]"}"""
      )
    }
  }

  it should "parse the date param into a date key" in {
    RestServiceRoutes.makeDateKey("2014-06-28") match {
      case Success(dateKey) => dateKey.toString() should equal ("2014-06-28T00:00:00.000+01:00")
      case Failure(t) => fail("Bad parse")
    }
  }

  // Todo: remove this. Stubbing is needed even though not used in the PUT route.
  // Spray route runner seems to be travelling down the "get" branch just 
  // before travelling the "put" branch.

  def applyStubForPutHack(crawlerDao: CrawlerDAO, id: String, crawler: Crawler) = {
    (crawlerDao.find(_:String)).when(id).returns(Some(crawler))
  }


  /* = = = = = = = = = =  Utility  = = = = = = = = = = */

  def makeConfig(id: String, uri: String) = CrawlConfig(
    id = id,
    userAgent = Some("Test Agent"),
    crawlerName = "Test Crawler",
    seeds = Seq(CrawlUri(uri)),
    uriFilter = new PriorityRejectUriFilter(Seq(Accept(uri.r))),
    tests = Some(Seq(s"should accept: $uri")),
    crawlDelayMillis = 0,
    crawlTimeoutMillis = 10000,
    maxDepth = 10,
    maxFetches = 10000,
    maxQueueSize = 10000,
    maxRequestFails = 0.5
  )

  def makeJob(crawlerId: String, jobId: String, jobDate: DateTime) = CrawlJob(
    crawlerId = crawlerId,
    crawlerName = "Test Crawler",
    jobId = jobId,
    node = "localhost",
    partitionDate = jobDate,
    snapshotDate = jobDate,
    createdDate = jobDate,
    finishedDate = Some(jobDate),
    duration = 1000,
    outcome = Some("Okay"),
    message = Some("Completed Okay"),
    urisSeen = 1000,
    urisQueued = 0,
    fetchCounters = Map.empty[String, Int],
    responseCounters = Map.empty[String, Int],
    mediaCounters = Map.empty[String, Media]
  )

  def makeDoc(crawlerId: String, jobId: String, uri: String) = DocumentMetaData(
    crawlerId = crawlerId,
    jobId = jobId,
    uri = uri,
    contentType = "text/html;charset=UTF=8",
    contentLength = 30000,
    depth = 0,
    fetched = new DateTime,
    responseStatus = "200"
  )

  def makeFetch(crawlerId: String, jobId: String, uri: String) = FetchLogEntry(
    crawlerId = crawlerId,
    jobId = jobId,
    logTime = new DateTime,
    uri = uri,
    uriDepth = 0,
    statusCode = 200,
    contentType = Some("text/html;charset=UTF-8"),
    contentLength = 30000,
    linksExtracted = 200,
    fetchDuration = 1005,
    requestDuration = 1000,
    parseDuration = 5,
    urisSeen = 1,
    urisQueued = 0,
    fetches = 1
  )

}

class Placeholder extends Actor { 
  override def receive = {
    case _ =>
  }
}