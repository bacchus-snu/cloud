// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.oidc.vo;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

import org.apache.cloudstack.api.InternalIdentity;

/**
 * A sign-in that has been sent to its provider and not come back yet. The state is kept only as a hash, and so is the
 * value of the browser that started it; a request is used at most once.
 */
@Entity
@Table(name = "oidc_login_request")
public class OidcLoginRequestVO implements InternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "provider_id")
    private long providerId;

    @Column(name = "state_hash")
    private String stateHash;

    @Column(name = "nonce")
    private String nonce;

    @Column(name = "pkce_verifier")
    private String pkceVerifier;

    @Column(name = "binding_hash")
    private String bindingHash;

    @Column(name = "redirect_uri", length = 2048)
    private String redirectUri;

    @Column(name = "created")
    @Temporal(TemporalType.TIMESTAMP)
    private Date created;

    public OidcLoginRequestVO() {
    }

    public OidcLoginRequestVO(long providerId, String stateHash, String nonce, String pkceVerifier, String bindingHash, String redirectUri, Date created) {
        this.providerId = providerId;
        this.stateHash = stateHash;
        this.nonce = nonce;
        this.pkceVerifier = pkceVerifier;
        this.bindingHash = bindingHash;
        this.redirectUri = redirectUri;
        this.created = created;
    }

    @Override
    public long getId() {
        return id;
    }

    public long getProviderId() {
        return providerId;
    }

    public String getStateHash() {
        return stateHash;
    }

    public String getNonce() {
        return nonce;
    }

    public String getPkceVerifier() {
        return pkceVerifier;
    }

    public String getBindingHash() {
        return bindingHash;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public Date getCreated() {
        return created;
    }
}
