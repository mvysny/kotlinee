package com.github.vok.example.crud

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import com.github.vok.example.crud.personeditor.MaritalStatus
import com.github.vok.example.crud.personeditor.Person
import com.github.vok.example.crud.personeditor.usingApp
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.javalin.Javalin
import khttp.responses.Response
import java.io.IOException
import java.time.LocalDate
import kotlin.test.expect

fun Response.checkOk(): Response {
    if (statusCode !in 200..299) throw IOException("$statusCode: $text ($url)")
    return this
}

class PersonRestClient(val baseUrl: String) {
    init {
        require(!baseUrl.endsWith("/")) { "$baseUrl must not end with a slash" }
    }
    val gson: Gson = GsonBuilder().create()
    fun helloWorld(): String = khttp.get("$baseUrl/person/helloworld").checkOk().text
    fun getAll(): List<Person> {
        val text = khttp.get("$baseUrl/person").checkOk().text
        val type = TypeToken.getParameterized(List::class.java, Person::class.java).type
        return gson.fromJson<List<Person>>(text, type)
    }
}

class PersonRestTest : DynaTest({
    lateinit var javalin: Javalin
    beforeGroup {
        javalin = Javalin.create().disableStartupBanner()
        javalin.configureRest().start(9876)
    }
    afterGroup { javalin.stop() }

    usingApp()  // to bootstrap the app to have access to the database.

    lateinit var client: PersonRestClient
    beforeEach { client = PersonRestClient("http://localhost:9876/rest") }

    test("hello world") {
        expect("Hello World") { client.helloWorld() }
    }

    test("get all users") {
        expectList() { client.getAll() }
        val p = Person(personName = "Duke Leto Atreides", age = 45, dateOfBirth = LocalDate.of(1980, 5, 1), maritalStatus = MaritalStatus.Single, alive = false)
        p.save()
        expectList(p) { client.getAll() }
    }
})
