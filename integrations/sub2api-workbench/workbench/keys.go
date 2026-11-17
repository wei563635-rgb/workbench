package workbench

import (
	"context"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"net/http"
)

type KeyRequest struct {
	UserID    int64  `json:"user_id"`
	GroupID   int64  `json:"group_id"`
	Purpose   string `json:"purpose"`
	CustomKey string `json:"custom_key"`
	KeyID     int64  `json:"key_id"`
}

var CreateKey func(context.Context, KeyRequest) (int64, error)
var DisableKey func(context.Context, KeyRequest) error

func keyOperation(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 8192)
	var req KeyRequest
	if json.NewDecoder(c.Request.Body).Decode(&req) != nil || req.UserID <= 0 || (req.Purpose != "client" && req.Purpose != "internal") {
		failure(c, 400, "INVALID_KEY_REQUEST")
		return
	}
	var active bool
	if database.QueryRowContext(c.Request.Context(), "SELECT enabled AND configured FROM wb_employee WHERE user_id=$1", req.UserID).Scan(&active) != nil {
		failure(c, 403, "USER_NOT_MANAGED")
		return
	}
	if c.Request.Method == "DELETE" {
		if DisableKey == nil || req.KeyID <= 0 {
			failure(c, 400, "INVALID_KEY_REQUEST")
			return
		}
		if DisableKey(c.Request.Context(), req) != nil {
			failure(c, 502, "KEY_DISABLE_FAILED")
			return
		}
		success(c, gin.H{"disabled": true})
		return
	}
	if !active || req.GroupID <= 0 || len(req.CustomKey) < 32 || len(req.CustomKey) > 128 || CreateKey == nil {
		failure(c, 400, "INVALID_KEY_REQUEST")
		return
	}
	id, err := CreateKey(c.Request.Context(), req)
	if err != nil {
		failure(c, 502, "KEY_CREATE_FAILED")
		return
	}
	if _, err = database.ExecContext(c.Request.Context(), "INSERT INTO wb_key VALUES($1,$2,$3) ON CONFLICT(key_id) DO NOTHING", id, req.UserID, req.Purpose); err != nil {
		failure(c, 503, "KEY_REGISTRATION_FAILED")
		return
	}
	success(c, gin.H{"id": id})
}
