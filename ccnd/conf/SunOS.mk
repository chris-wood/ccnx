MORE_CFLAGS = -mt -K pic
MORE_LDLIBS = -L/usr/apache2/lib -lmtmalloc -lnsl -lsocket
CPREFLAGS = -I../include -I/usr/apache2/include -DXML_STATUS_OK=0 -I/usr/local/ssl/include
SHEXT = so
SHLIBNAME=libccn.$(SHEXT).1
SHLIBDEPS=
SHARED_LD_FLAGS = -G -z allextract
PLATCFLAGS = -KPIC
