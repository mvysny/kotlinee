[![GitHub tag](https://img.shields.io/github/tag/mvysny/vaadin-on-kotlin.svg)](https://github.com/mvysny/vaadin-on-kotlin/tags)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/eu.vaadinonkotlin/vok-rest/badge.svg)](https://maven-badges.herokuapp.com/maven-central/eu.vaadinonkotlin/vok-rest)

# VoK REST Server Support

This module makes it easy to export your objects via REST. The aim here is to use as lightweight libraries as possible,
that's why we're using [Javalin](https://javalin.io/) for REST endpoint definition, and [Gson](https://github.com/google/gson) for object-to-JSON mapping instead of
Jackson.

> Note: this module does not have any support for your app to *consume* and *display* data from an external REST services.
Please follow the [Accessing NoSQL or REST data sources](http://www.vaadinonkotlin.eu/nosql_rest_datasources.html) guide for more information.
Also visit [vok-rest-client](../vok-rest-client) for consuming REST services easily with VOK apps.

## Adding REST Server To Your App

Include dependency on this module to your app; just add the following Gradle dependency to your `build.gradle`:

```groovy
dependencies {
    implementation("eu.vaadinonkotlin:vok-rest:x.y.z")
}
```

> Note: to obtain the newest version see above for the most recent tag

Now you can write the REST endpoint interface. We are going to introduce a new servlet that will handle all REST-related calls;
we're going to reroute all calls to Javalin which will then parse REST requests; we're then going to configure Javalin with our REST
endpoints:

```kotlin
/**
 * Provides access to person list. To test, just run `curl http://localhost:8080/rest/person`
 */
@WebServlet(urlPatterns = ["/rest/*"], name = "JavalinRestServlet", asyncSupported = false)
class JavalinRestServlet : HttpServlet() {
    val javalin = EmbeddedJavalin()
            .configureRest()
            .createServlet()

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        javalin.service(req, resp)
    }
}

fun Javalin.configureRest(): Javalin {
    val gson = GsonBuilder().create()
    gson.configureToJavalin()
    get("/rest/person/helloworld") { ctx -> ctx.result("Hello World") }
    get("/rest/person/helloworld2") { ctx -> ctx.json(Person.findAll()) }  // uses Gson
    crud2("/rest/person", Person.getCrudHandler(true))  // a full-blown CRUD Handler
    return this
}
```

> *Example:* You can find the `JavalinRestServlet` class in action in the [vaadin10-restdataprovider-example](https://gitlab.com/mvysny/vaadin10-restdataprovider-example)
example application. It's located in the `Bootstrap.kt` file.

To test it out, just run the following in your command line:

```bash
curl http://localhost:8080/rest/person
```

This should hit the route defined via the `crud2("/rest/person")` and should print all personnel in your database.

Please consult [Javalin Documentation](https://javalin.io/documentation) for more details on how to configure REST endpoints.

### CRUD Handler

VoK provides two REST CRUD handlers by default:

* The `DataLoaderCrudHandler` which exposes contents of any [DataLoader](https://gitlab.com/mvysny/vok-dataloader)
  over REST (the `crud2()` function as seen above), including paging, filtering and sorting, but it provides no support for POST/PATCH/DELETE;
* The `VokOrmCrudHandler` which provides the same as the `DataLoaderCrudHandler` but it includes support for POST/PATCH/DELETE.
  This Handler will automatically use vok-orm's `EntityDataProvider` to fetch instances of the entity.

Attaching the CRUD handler to, say, `/rest/users` will export the following endpoints:

* `GET /rest/users` returns all users
* `GET /rest/users/22` returns one user
* `POST /rest/users` will create an user (only for `VokOrmCrudHandler`; `DataLoaderCrudHandler` will simply fail with 401 UNAUTHORIZED)
* `PATCH /rest/users/22` will update an user (only for `VokOrmCrudHandler`; `DataLoaderCrudHandler` will simply fail with 401 UNAUTHORIZED)
* `DELETE /rest/users/22` will delete an user (only for `VokOrmCrudHandler`; `DataLoaderCrudHandler` will simply fail with 401 UNAUTHORIZED)

The `get all` endpoint supports the following query parameters:

* `limit` and `offset` for result paging. Both must be 0 or greater; `limit` must be less than `maxLimit`
* `sort_by=-lastModified,+email,firstName` - a list of sorting clauses.
Only those which appear in `allowSortColumns` are allowed. Prepending a column name with
`-` will sort DESC. Prepending the column name with `+` is optional and can be omitted.
* To define filters, simply pass in column names with the values, for example `age=81`. You can also specify operators: one of
`eq:`, `lt:`, `lte:`, `gt:`, `gte:`, `ilike:`, `like:`, `isnull:`, `isnotnull:`, for example `age=lt:25`. You can pass single column name
multiple times to AND additional clauses, for example `name=ilike:martin&age=lte:70&age=gte:20&birthdate=isnull:&grade=5`. OR filters are not supported.
* `select=count` - if this is passed in, then instead of a list of matching objects a single number will be returned: the number of
records matching given filters.

All column names are expected to be Kotlin property names of the entity in question.

### Testing your REST endpoints

You can easily start Javalin with Jetty which allows you to test your REST endpoints on an actual http server. You need to add the following dependencies:

```gradle
dependencies {
    testImplementation("com.github.vok:vok-rest-client:x.y.z")
    testImplementation("org.eclipse.jetty.websocket:websocket-server:9.4.12.v20180830")
}
```

This will add Jetty for booting up a testing Javalin server; we're going to access the REST endpoints via the [vok-rest-client](../vok-rest-client) VOK module.

The testing file will look like this:

```kotlin
// Demoes Retrofit + annotations client
interface PersonRestClient {
    @GET("helloworld")
    @Throws(IOException::class)
    fun helloWorld(): String

    @GET(".")
    @Throws(IOException::class)
    fun getAll(): List<Person>
}

// Demoes direct access via okhttp
class PersonRestClient2(val baseUrl: String) {
    private val client: OkHttpClient = RetrofitClientVokPlugin.okHttpClient!!
    fun helloWorld(): String {
        val request = Request.Builder().url("${baseUrl}helloworld").build()
        return client.exec(request) { response -> response.string() }
    }
    fun getAll(): List<Person> {
        val request = Request.Builder().url(baseUrl).build()
        return client.exec(request) { response -> response.jsonArray(Person::class.java) }
    }
}

fun DynaNodeGroup.usingRestClient() {
    beforeGroup { RetrofitClientVokPlugin().init() }
    afterGroup { RetrofitClientVokPlugin().destroy() }
}

class PersonRestTest : DynaTest({
    lateinit var javalin: Javalin
    beforeGroup {
        javalin = Javalin.create().disableStartupBanner()
        javalin.configureRest().start(9876)
    }
    afterGroup { javalin.stop() }

    usingDb()  // to have access to the database.
    usingRestClient()

    test("hello world") {
        val client = createRetrofit("http://localhost:9876/rest/person/").create(PersonRestClient::class.java)
        expect("Hello World") { client.helloWorld() }
        val p = Person(personName = "Duke Leto Atreides", age = 45, dateOfBirth = LocalDate.of(1980, 5, 1), maritalStatus = MaritalStatus.Single, alive = false)
        p.save()
        expectList(p) { client.getAll() }
    }

    test("hello world 2") {
        val client = PersonRestClient2("http://localhost:9876/rest/person/")
        expect("Hello World") { client.helloWorld() }
        val p = Person(personName = "Duke Leto Atreides", age = 45, dateOfBirth = LocalDate.of(1980, 5, 1), maritalStatus = MaritalStatus.Single, alive = false)
        p.save()
        expectList(p) { client.getAll() }
    }
}
```

Please consult the [vok-example-crud-vokdb](../vok-example-crud-vokdb) example project for more info.

## Customizing JSON mapping

Gson by default only export non-transient fields. It only exports actual Java fields, or only Kotlin properties that are backed by actual fields;
it ignores computed Kotlin properties such as `val reviews: List<Review> get() = Review.findAll()`.

Please see [Gson User Guide](https://github.com/google/gson/blob/master/UserGuide.md) for more details.
