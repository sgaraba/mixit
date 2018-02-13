package mixit.web.handler

import mixit.MixitProperties
import mixit.model.Language
import mixit.model.Link
import mixit.model.Role
import mixit.model.User
import mixit.repository.TalkRepository
import mixit.repository.UserRepository
import mixit.util.*
import mixit.util.validator.EmailValidator
import mixit.util.validator.MarkdownValidator
import mixit.util.validator.MaxLengthValidator
import mixit.util.validator.UrlValidator
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.created
import org.springframework.web.reactive.function.server.ServerResponse.ok
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.bodyToMono
import org.springframework.web.util.UriUtils
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import java.net.URI.create
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.stream.IntStream


@Component
class UserHandler(private val repository: UserRepository,
                  private val talkRepository: TalkRepository,
                  private val markdownConverter: MarkdownConverter,
                  private val cryptographer: Cryptographer,
                  private val properties: MixitProperties,
                  private val emailValidator: EmailValidator,
                  private val urlValidator: UrlValidator,
                  private val maxLengthValidator: MaxLengthValidator,
                  private val markdownValidator: MarkdownValidator) {

    companion object {
        val speakerStarInHistory = listOf(
                "tastapod",
                "joel.spolsky",
                "pamelafox",
                "MattiSG",
                "bodil",
                "mojavelinux",
                "andrey.breslav",
                //"kowen",
                "ppezziardi",
                "rising.linda")
        val speakerStarInCurrentEvent = listOf(
                "jhoeller@pivotal.io",
                "sharon@sharonsteed.co",
                "agilex",
                "laura.carvajal@gmail.com",
                "augerment@gmail.com",
                "dgageot",
                "romainguy@curious-creature.com",
                "graphicsgeek1@gmail.com",
                "sam@sambrannen.com")
    }


    fun findOneView(req: ServerRequest) =
            try {
                val idLegacy = req.pathVariable("login").toLong()
                repository.findByLegacyId(idLegacy).flatMap { findOneViewDetail(it, "user", false, req, emptyMap()) }
            } catch (e: NumberFormatException) {
                repository.findOne(URLDecoder.decode(req.pathVariable("login"), "UTF-8"))
                        .flatMap { findOneViewDetail(it, "user", false, req, emptyMap()) }
            }

    fun findProfile(req: ServerRequest) =
            req.session().flatMap {
                val currentUserEmail = it.getAttribute<String>("email")
                repository.findByEmail(currentUserEmail!!).flatMap { findOneViewDetail(it, "user", true, req, emptyMap()) }
            }

    fun editProfile(req: ServerRequest) =
            req.session().flatMap {
                val currentUserEmail = it.getAttribute<String>("email")
                repository.findByEmail(currentUserEmail!!).flatMap { findOneViewDetail(it, "profile", false, req, emptyMap()) }
            }


    fun saveProfile(req: ServerRequest): Mono<ServerResponse> = req.session().flatMap {
        val currentUserEmail = it.getAttribute<String>("email")
        req.body(BodyExtractors.toFormData()).flatMap {

            val formData = it.toSingleValueMap()

            // In his profile screen a user can't change all the data. In the first step we load the user
            repository.findByEmail(currentUserEmail!!).flatMap {

                val errors = mutableMapOf<String, String>()

                // Null check
                if(formData["firstname"].isNullOrBlank()){
                    errors.put("firstname","user.form.error.firstname.required")
                }
                if(formData["lastname"].isNullOrBlank()){
                    errors.put("lastname","user.form.error.lastname.required")
                }
                if(formData["email"].isNullOrBlank()){
                    errors.put("email","user.form.error.email.required")
                }
                if(formData["description-fr"].isNullOrBlank()){
                    errors.put("description-fr","user.form.error.description.fr.required")
                }
                if(formData["description-en"].isNullOrBlank()){
                    errors.put("description-en","user.form.error.description.en.required")
                }

                if(errors.isNotEmpty()){
                    findOneViewDetail(it, "profile", false, req, errors)
                }

                val user = User(
                        it.login,
                        formData["firstname"]!!,
                        formData["lastname"]!!,
                        cryptographer.encrypt(formData["email"]!!),
                        if (formData["company"] == "") null else formData["company"],
                        mapOf(
                                Pair(Language.FRENCH, markdownValidator.sanitize(formData["description-fr"]!!)),
                                Pair(Language.ENGLISH, markdownValidator.sanitize(formData["description-en"]!!))),
                        if (formData["photoUrl"].isNullOrBlank()) formData["email"]!!.encodeToMd5() else null,
                        if (formData["photoUrl"] == "") null else formData["photoUrl"],
                        it.role,
                        extractLinks(formData),
                        it.legacyId,
                        it.tokenExpiration,
                        it.token
                )



                // We want to control data to not save invalid things in our database
                if(!maxLengthValidator.isValid(user.firstname, 30)){
                    errors.put("firstname","user.form.error.firstname.size")
                }
                if(!maxLengthValidator.isValid(user.lastname, 30)){
                    errors.put("lastname","user.form.error.lastname.size")
                }
                if(user.company!=null && !maxLengthValidator.isValid(user.company, 60)){
                    errors.put("company","user.form.error.company.size")
                }
                if(!emailValidator.isValid(formData["email"]!!)){
                    errors.put("email","user.form.error.email")
                }
                if(!markdownValidator.isValid(user.description.get(Language.FRENCH))){
                    errors.put("description-fr","user.form.error.description.fr")
                }
                if(!markdownValidator.isValid(user.description.get(Language.ENGLISH))){
                    errors.put("description-en","user.form.error.description.en")
                }
                if(!urlValidator.isValid(user.photoUrl)){
                    errors.put("photoUrl","user.form.error.photourl")
                }
                user.links.forEachIndexed { index, link ->
                    if(!maxLengthValidator.isValid(link.name, 30)){
                        errors.put("link${index+1}Name","user.form.error.link${index+1}.name")
                    }
                    if(!urlValidator.isValid(link.url)){
                        errors.put("link${index+1}Url","user.form.error.link${index+1}.url")
                    }
                }

                if(errors.isEmpty()){
                    // If everything is Ok we save the user
                    repository.save(user).then(seeOther("${properties.baseUri}/me"))
                }
                else{
                    findOneViewDetail(user, "profile", false, req, errors)
                }
            }
        }
    }

    private fun extractLinks(formData: Map<String, String>): List<Link> =
        IntStream.range(0,5)
                .toArray()
                .asList()
                .mapIndexed { index, i -> Pair(formData["link${index}Name"], formData["link${index}Url"]) }
                .filter { !it.first.isNullOrBlank() && !it.second.isNullOrBlank() }
                .map { Link(it.first!!, it.second!!) }



    private fun findOneViewDetail(user: User, view: String, canUpdateProfile: Boolean, req: ServerRequest, errors:Map<String, String>) =
            talkRepository
                    .findBySpeakerId(listOf(user.login))
                    .collectList()
                    .flatMap { talks ->
                        talks.map { talk -> talk.toDto(req.language(), listOf(user)) }.toMono()
                        if ("profile".equals(view))
                            ok()
                                    .render("profile", mapOf(
                                            Pair("user", user.toDto(req.language(), markdownConverter)),
                                            Pair("usermail", cryptographer.decrypt(user.email)),
                                            Pair("description-fr", user.description[Language.FRENCH]),
                                            Pair("description-en", user.description[Language.ENGLISH]),
                                            Pair("userlinks", user.toLinkDtos()),
                                            Pair("baseUri", UriUtils.encode(properties.baseUri!!, StandardCharsets.UTF_8)),
                                            Pair("errors", errors),
                                            Pair("hasErrors", errors.isNotEmpty())
                                    ))
                        else
                            ok()
                                    .render(view, mapOf(
                                            Pair("user", user.toDto(req.language(), markdownConverter)),
                                            Pair("canUpdateProfile", canUpdateProfile),
                                            Pair("talks", talks),
                                            Pair("hasTalks", talks.isNotEmpty()),
                                            Pair("baseUri", UriUtils.encode(properties.baseUri!!, StandardCharsets.UTF_8))
                                    ))
                    }


    fun findOne(req: ServerRequest) = ok().json().body(repository.findOne(req.pathVariable("login")))

    fun findAll(req: ServerRequest) = ok().json().body(repository.findAll())

    fun findStaff(req: ServerRequest) = ok().json().body(repository.findByRoles(listOf(Role.STAFF)))

    fun findOneStaff(req: ServerRequest) = ok().json().body(repository.findOneByRoles(req.pathVariable("login"), listOf(Role.STAFF, Role.STAFF_IN_PAUSE)))

    fun create(req: ServerRequest) = repository.save(req.bodyToMono<User>()).flatMap {
        created(create("/api/user/${it.login}")).json().body(it.toMono())
    }

}

class LinkDto(
        val name: String,
        val url: String,
        val index: String)

fun Link.toLinkDto(index: Int) = LinkDto(name, url, "link${index + 1}")

fun User.toLinkDtos() = if (links.size > 4) links else {
    val existingLinks = links.size
    val userLinks = links.mapIndexed { index, link -> link.toLinkDto(index) }.toMutableList()
    IntStream.range(0, 5 - existingLinks).forEach { userLinks.add(LinkDto("", "", "link${existingLinks + it + 1}")) }
    userLinks.groupBy { it.index }
}

class SpeakerStarDto(
        val login: String,
        val key: String,
        val name: String
)

fun User.toSpeakerStarDto() = SpeakerStarDto(login, lastname.toLowerCase(), "$firstname $lastname")

class UserDto(
        val login: String,
        val firstname: String,
        val lastname: String,
        var email: String? = null,
        var company: String? = null,
        var description: String,
        var emailHash: String? = null,
        var photoUrl: String? = null,
        val role: Role,
        var links: List<Link>,
        val logoType: String?,
        val logoWebpUrl: String? = null
)

fun User.toDto(language: Language, markdownConverter: MarkdownConverter) =
        UserDto(login,
                firstname,
                lastname,
                email,
                company, markdownConverter.toHTML(description[language] ?: ""),
                emailHash,
                photoUrl,
                role,
                links,
                logoType(photoUrl),
                logoWebpUrl(photoUrl))

fun logoWebpUrl(url: String?) =
        when {
            url == null -> null
            url.endsWith("png") -> url.replace("png", "webp")
            url.endsWith("jpg") -> url.replace("jpg", "webp")
            else -> null
        }

fun logoType(url: String?) =
        when {
            url == null -> null
            url.endsWith("svg") -> "image/svg+xml"
            url.endsWith("png") -> "image/png"
            url.endsWith("jpg") -> "image/jpeg"
            url.endsWith("gif") -> "image/gif"
            else -> null
        }
