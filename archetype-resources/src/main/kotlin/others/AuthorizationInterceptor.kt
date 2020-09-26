package ${package_name}.others

import ${package_name}.annotations.AllowAnonymous
import ${package_name}.annotations.AllowSignedIn
import ${package_name}.annotations.BuiltInRole
import ${package_name}.constants.AppConstants
import ${package_name}.helpers.ResponseHelper
import ${package_name}.helpers.TokenHelper
import ${package_name}.models.Response
import ${package_name}.models.User
import ${package_name}.services.PermissionService
import ${package_name}.services.RoleService
import ${package_name}.services.UserService
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import javax.annotation.Resource
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component
class AuthorizationInterceptor : HandlerInterceptor {

    @Resource
    lateinit var tokenHelper: TokenHelper

    @Resource
    lateinit var responseHelper: ResponseHelper

    @Resource
    lateinit var userService: UserService

    @Resource
    lateinit var roleService: RoleService

    @Resource
    lateinit var permissionService: PermissionService

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) {
            return true
        }

        response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"))
        response.setHeader("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers")

        val builtInRole = handler.method.getAnnotation(BuiltInRole::class.java)
        val allowAnonymous = handler.method.getAnnotation(AllowAnonymous::class.java) != null
        val allowSignedIn = handler.method.getAnnotation(AllowSignedIn::class.java) != null

        if (request.method == RequestMethod.OPTIONS.name) {
            responseHelper.setResponse(response, Response.success())
            return false
        }

        // 设置终端类型
        val service = request.getHeader(AppConstants.SERVICE) ?: AppConstants.Service.DEFAULT
        request.setAttribute(AppConstants.SERVICE, service)

        // 允许匿名访问，直接通过
        if (allowAnonymous) {
            return true
        }

        val token = request.getHeader(AppConstants.TOKEN)

        // 不存在token，不通过
        if ((token == null || token.isEmpty())) {
            responseHelper.setResponse(response, Response.Errors.tokenNotFound())
            return false
        }

        // 万能Token
        if (token == "2njpzZ1JdtCBfTsy" || token == "bean") {
            return true
        }

        val key = tokenHelper.getTokenKey(service, token)

        // Token 失效
        if (key == null) {
            responseHelper.setResponse(response, Response.Errors.tokenInvalid())
            return false
        }

        // Token有效
        request.setAttribute(AppConstants.KEY, key)

        // 获取 user
        val user = userService.getUserByKey(service, key)
        if (user == null) {
            responseHelper.setResponse(response, Response.Errors.tokenInvalid())
            return false
        }

        // 允许已登录用户访问，直接通过
        if (allowSignedIn) {
            return true
        }

        // 验证代码内置权限
        if (builtInRole != null && builtInRole.roles.isNotEmpty()) {
            for (role in builtInRole.roles) {
                // 拥有其中一种权限则通过
                if (checkBuiltInRole(user, role)) {
                    return true
                }
            }
        }

        // 验证 Rbac 权限
        val controllerMappingPath = handler.bean.javaClass.getAnnotation(RequestMapping::class.java).value[0]
        val methodMappingPath = handler.method.getAnnotation(RequestMapping::class.java).value[0]
        if (checkRbacPermission(user, request.method, "$${controllerMappingPath}$${methodMappingPath}")) {
            return true
        }

        // 权限不足
        responseHelper.setResponse(response, Response.Errors.permissionDenied())
        return false
    }

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) = Unit

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) = Unit

    private fun checkBuiltInRole(user: User, role: String): Boolean {
        val userRoles = roleService.getRolesByUserId(user.id)
        return userRoles.any { r -> r.key == role }
    }

    private fun checkRbacPermission(user: User, method: String, path: String): Boolean {
        val userPermissions = permissionService.getPermissionsByUserId(user.id)
        return userPermissions.any { p -> p.apiMethod == method && p.apiPath == path }
    }
}
