/*
 * Injects chunks of data from stdin into ccn
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/uri.h>
#include <ccn/keystore.h>

struct mydata {
    int content_received;
    int content_sent;
    int outstanding;
};

enum ccn_upcall_res
incoming_content(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind == CCN_UPCALL_INTEREST_TIMED_OUT)
        return(CCN_UPCALL_RESULT_OK);
    if (kind != CCN_UPCALL_CONTENT || md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    md->content_received++;
    ccn_set_run_timeout(info->h, 0);
    return(CCN_UPCALL_RESULT_OK);
}

enum ccn_upcall_res
incoming_interest(
    struct ccn_closure *selfp,
    enum ccn_upcall_kind kind,
    struct ccn_upcall_info *info)
{
    struct mydata *md = selfp->data;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind != CCN_UPCALL_INTEREST || md == NULL)
        return(CCN_UPCALL_RESULT_ERR);
    if ((info->pi->answerfrom & 2) != 0) {
        md->outstanding += info->pi->count + 9;
        ccn_set_run_timeout(info->h, 0);
    }
    return(CCN_UPCALL_RESULT_OK);
}

ssize_t
read_full(int fd, unsigned char *buf, size_t size)
{
    size_t i;
    ssize_t res = 0;
    for (i = 0; i < size; i += res) {
        res = read(fd, buf + i, size - i);
        if (res == -1) {
            if (errno == EAGAIN || errno == EINTR)
                res = 0;
            else
                return(res);
        }
        else if (res == 0)
            break;
    }
    return(i);
}

int
main(int argc, char **argv)
{
    struct ccn *ccn = NULL;
    struct ccn_charbuf *root = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *templ = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_keystore *keystore = NULL;
    int i;
    int status = 0;
    int res;
    ssize_t read_res;
    unsigned char *buf = NULL;
    struct mydata mydata = { 0 };
    struct ccn_closure in_content = {.p=&incoming_content, .data=&mydata};
    struct ccn_closure in_interest = {.p=&incoming_interest, .data=&mydata};
    if (argv[1] == NULL) {
        fprintf(stderr,
                "%s: Chops stdin into 1K blocks and sends them "
                "as consecutively numbered ContentObjects "
                "under the given uri\n", argv[0]);
        exit(1);
    }
    else {
        name = ccn_charbuf_create();
        res = ccn_name_from_uri(name, argv[1]);
        if (res < 0) {
            fprintf(stderr, "%s: bad ccn URI: %s\n", argv[0], argv[1]);
            exit(1);
        }
        if (argv[2] != NULL)
            fprintf(stderr, "%s warning: extra arguments ignored\n", argv[0]);
    }

    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1) {
        perror("Could not connect to ccnd");
        exit(1);
    }
    
    
    buf = calloc(1, 1024);
    root = name;
    name = ccn_charbuf_create();
    temp = ccn_charbuf_create();
    templ = ccn_charbuf_create();
    signed_info = ccn_charbuf_create();
    keystore = ccn_keystore_create();
    temp->length = 0;
    ccn_charbuf_putf(temp, "%s/.ccn/.ccn_keystore", getenv("HOME"));
    res = ccn_keystore_init(keystore,
                            ccn_charbuf_as_string(temp),
                            "Th1s1sn0t8g00dp8ssw0rd.");
    if (res != 0) {
        printf("Failed to initialize keystore\n");
        exit(1);
    }
    
    name->length = 0;
    ccn_charbuf_append(name, root->buf, root->length);
    
    /* Set up a handler for interests */
    ccn_set_interest_filter(ccn, name, &in_interest);
    
    /* Initiate check to see whether there is already something there. */
    temp->length = 0;
    ccn_charbuf_putf(temp, "%d", 0);
    ccn_name_append(name, temp->buf, temp->length);
    templ->length = 0;
    ccn_charbuf_append_tt(templ, CCN_DTAG_Interest, CCN_DTAG);
    ccn_charbuf_append_tt(templ, CCN_DTAG_Name, CCN_DTAG);
    ccn_charbuf_append_closer(templ); /* </Name> */
    ccn_charbuf_append_tt(templ, CCN_DTAG_AdditionalNameComponents, CCN_DTAG);
    ccn_charbuf_append_tt(templ, 1, CCN_UDATA);
    ccn_charbuf_append(templ, "1", 1);
    ccn_charbuf_append_closer(templ); /* </AdditionalNameComponents> */
    // XXX - use pubid
    ccn_charbuf_append_closer(templ); /* </Interest> */
    res = ccn_express_interest(ccn, name, -1, &in_content, templ);
    if (res < 0) abort();
    ccn_run(ccn, 0); /* send the interest out */

    for (i = 0;; i++) {
        read_res = read_full(0, buf, 1024);
        if (read_res < 0) {
            perror("read");
            read_res = 0;
            status = 1;
        }
        signed_info->length = 0;
        res = ccn_signed_info_create(signed_info,
                                     /*pubkeyid*/NULL,
                                     /*publisher_key_id_size*/0,
                                     /*datetime*/NULL,
                                     /*type*/CCN_CONTENT_LEAF,
                                     /*freshness*/ 10,
                                     /*keylocator*/NULL);
        if (res < 0) {
            fprintf(stderr, "Failed to create signed_info (res == %d)\n", res);
            exit(1);
        }
        name->length = 0;
        ccn_charbuf_append(name, root->buf, root->length);
        temp->length = 0;
        ccn_charbuf_putf(temp, "%d", i);
        ccn_name_append(name, temp->buf, temp->length);
        temp->length = 0;
        ccn_charbuf_append(temp, buf, read_res);
        temp->length = 0;
        res = ccn_encode_ContentObject(temp,
                                       name,
                                       signed_info,
                                       buf,
                                       read_res,
                                       NULL,
                                       ccn_keystore_private_key(keystore));
        if (res != 0) {
            fprintf(stderr, "Failed to encode ContentObject (res == %d)\n", res);
            exit(1);
        }
        if (i == 0) {
            /* Finish check for old content */
            if (mydata.content_received == 0)
                ccn_run(ccn, 50);
            if (mydata.content_received > 0) {
                fprintf(stderr, "%s: name is in use: %s\n", argv[0], argv[1]);
                exit(1);
            }
            mydata.outstanding++; /* the first one is free... */
        }
        res = ccn_put(ccn, temp->buf, temp->length);
        if (res < 0) {
            fprintf(stderr, "ccn_put failed (res == %d)\n", res);
            exit(1);
        }
        if (read_res < 1024)
            break;
        if (mydata.outstanding > 0)
            mydata.outstanding--;
        else
            res = 10;
        ccn_run(ccn, res * 100);
    }
    
    free(buf);
    buf = NULL;
    ccn_charbuf_destroy(&root);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&signed_info);
    ccn_keystore_destroy(&keystore);
    ccn_destroy(&ccn);
    exit(status);
}
